package zcd.jellyfish.core.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.notification.CachePrefixChangedEvent;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmTool;
import zcd.jellyfish.infra.llm.LlmToolCall;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 缓存断裂观察器：对比同一会话相邻两次请求的「可缓存前缀」，把断裂点定位出来。
 * <p>
 * <b>为什么需要它</b>：厂商的 prompt 缓存是前缀匹配，改一个字节 → 其后全部失效。而「这一轮比上一轮
 * 多了什么、少了什么」在内核里没有任何地方留档，缓存断裂因此是<b>完全静默</b>的：用户只在账单上
 * 看到它，等到那时已经无从知道是哪一处改的。本类把那件事变成一条日志。
 * <p>
 * <b>它回答三个问题</b>，也就是三层前缀、顺序即重要性：
 * <ol>
 *     <li><b>system prompt 变了吗</b>——它是请求的第 0 个 token，一变则整段请求失效。
 *     <b>这一层如今不该再变</b>：摘要已挪进消息区、易变状态走回合上下文，剩下能进来的只有
 *     agent 提示词与插件贡献块，两者都不随轮次变。因此这里报出断裂时应当成 bug 查，
 *     而不是当现状接受；</li>
 *     <li><b>工具清单变了吗</b>——它在多数厂商的模板里排在 messages 之前，一变同样作废整段；</li>
 *     <li><b>消息序列还是「上一轮之后追加」吗</b>——被改写（压缩边界前移、旧工具结果被老化）会让
 *     改动点之后的内容全部失效。这一层最容易被忽略，因为它看起来「什么都没变」。</li>
 * </ol>
 * <p>
 * <b>只比较、不改请求</b>：本类在组装路径上被调用，只读入参、只写自己的观察状态，
 * 不碰 {@link LlmMessage} 也不碰会话——与 {@link ContextWindow} / {@link ToolResultAger} 同一口径。
 * <p>
 * <b>结论会广播出去</b>：除了日志，断裂还会发一条 {@link CachePrefixChangedEvent}，让插件能据此做
 * 阈值守卫、告警与趋势统计。两者的节流口径<b>刻意不同</b>：日志里的 WARN 断裂持续时只报一次
 * （刷屏会让日志不可读），而事件逐轮发——下一轮的前缀如果仍与这一轮不连续，那就是又一次真实的
 * 缓存损失，计数型订阅方需要看到每一次。
 * <p>
 * <b>为什么按会话而不是全局</b>：跨会话的相同前缀当然也能共享缓存，但「有没有断裂」只有在同一个
 * 会话的相邻两轮之间才有意义；混在一起看，只会得到一份读不出因果的统计。
 * <p>
 * <b>告警按「一轮断裂」节流</b>：只要会话足够长，{@code ToolResultAger} 的老化就会
 * <b>每轮都改写历史中段</b>（见 {@code docs/design/llm-cache.md} 的 R2），逐轮 WARN 会把日志刷满。
 * 因此只在「从稳定变为断裂」的那一轮告警，断裂持续期间降到 DEBUG；恢复之后再次断裂会重新告警。
 * <p>
 * <b>状态有界</b>：只保留最近 {@value #MAX_SESSIONS} 个会话的指纹，按访问顺序淘汰。会话关闭后
 * 留着它只是占内存，而最坏情况退化成「多观察一轮」。指纹只存消息正文的 32 位散列而不是正文本身，
 * 否则长会话会按「消息数 × 正文长度 × 会话数」占内存。
 * <p>
 * 线程安全：{@code react} 池线程会并发组装不同会话，全部入口同步。
 *
 * @author zcd
 */
@Singleton
public class CacheBreakWatcher {

    /** 最多同时观察多少个会话。 */
    private static final int MAX_SESSIONS = 64;

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(CacheBreakWatcher.class);

    /** 事件通道：把断裂广播给包括插件在内的订阅方。 */
    private final EventPublisher eventPublisher;

    /** 会话标识 → 上一轮的指纹，按访问顺序淘汰。 */
    private final Map<String, Snapshot> snapshots = new LruSnapshots();

    /**
     * 构造观察器。
     *
     * @param eventPublisher 事件通道，不可为 {@code null}
     */
    @Inject
    public CacheBreakWatcher(EventPublisher eventPublisher) {
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
    }

    /**
     * 记录本轮指纹并与该会话上一轮对比。
     *
     * @param sessionId    会话标识；为 {@code null} 时只出结论、不记状态
     * @param systemPrompt 本轮的 system prompt，可为 {@code null}（不下发）
     * @param tools        本轮的工具清单，不可为 {@code null}
     * @param messages     本轮实际要发送的历史消息，不可为 {@code null}
     * @return 观察结论，保证非 {@code null}
     */
    public Report observe(String sessionId, String systemPrompt, List<LlmTool> tools, List<LlmMessage> messages) {
        Objects.requireNonNull(tools, "tools must not be null");
        Objects.requireNonNull(messages, "messages must not be null");
        Snapshot current = Snapshot.of(systemPrompt, tools, messages);
        if (sessionId == null) {
            // 没有键就无从归属。首次观察的结论必然是「无从比较」，因此报它比编一个更有用
            return Report.between(null, current, false);
        }
        synchronized (this) {
            Snapshot previous = snapshots.get(sessionId);
            Report report = Report.between(previous, current, previous != null && previous.broken);
            snapshots.put(sessionId, current.withBroken(report.isBroken()));
            publish(sessionId, report);
            return report;
        }
    }

    /**
     * 广播一条断裂事件，不该报时什么都不做。
     * <p>
     * <b>订阅方出错不能影响请求</b>：本类是诊断设施，一个坏订阅方不该让整轮对话发不出去——
     * 与「插件贡献块失败只记告警」是同一种取舍。
     *
     * @param sessionId 会话标识，不可为 {@code null}
     * @param report    本轮结论
     */
    private void publish(String sessionId, Report report) {
        if (!report.isBroken() || report.isFirstObservation()) {
            // 首次观察没有基线，无从判断有没有断，因此不该报（否则每个新会话都白报一次）
            return;
        }
        try {
            eventPublisher.publish(new CachePrefixChangedEvent(sessionId, report.firstBrokenLayer(),
                    report.reusableMessages, report.previousMessages, report.currentMessages,
                    report.systemPromptLengthDelta));
        } catch (RuntimeException e) {
            LOG.warn("缓存断裂事件广播失败（已忽略，不影响对话）: sessionId={} reason={}",
                    sessionId, e.getMessage());
        }
    }

    /**
     * 按访问顺序淘汰最旧会话的映射。
     * <p>
     * 写成具名内部类而不是匿名类：{@code LinkedHashMap} 是可序列化的，匿名子类会让 Sonar 追着要
     * {@code serialVersionUID}，而这里根本不需要序列化语义。
     *
     * @author zcd
     */
    private static final class LruSnapshots extends LinkedHashMap<String, Snapshot> {

        /** 序列化标识（本类从不序列化，仅为满足 Serializable 约定）。 */
        private static final long serialVersionUID = 1L;

        /**
         * 构造按访问顺序排序的映射。
         */
        LruSnapshots() {
            super(16, 0.75f, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Snapshot> eldest) {
            return size() > MAX_SESSIONS;
        }
    }

    /**
     * 一轮请求的指纹。不可变，{@code messageHashes} 按约定只读。
     */
    private static final class Snapshot {

        /** system prompt 的散列；未下发时为 0。 */
        private final int systemPromptHash;

        /** system prompt 的长度，用于报告「变了多少」；未下发时为 0。 */
        private final int systemPromptLength;

        /** 工具清单的散列（含名称、描述、参数、顺序）。 */
        private final int toolsHash;

        /** 按顺序的消息指纹。 */
        private final List<Integer> messageHashes;

        /** 本轮相对上一轮是否断裂。 */
        private final boolean broken;

        /**
         * 构造指纹。
         *
         * @param systemPromptHash   system prompt 散列
         * @param systemPromptLength system prompt 长度
         * @param toolsHash          工具清单散列
         * @param messageHashes      消息指纹
         * @param broken             是否断裂
         */
        private Snapshot(int systemPromptHash, int systemPromptLength, int toolsHash,
                         List<Integer> messageHashes, boolean broken) {
            this.systemPromptHash = systemPromptHash;
            this.systemPromptLength = systemPromptLength;
            this.toolsHash = toolsHash;
            this.messageHashes = messageHashes;
            this.broken = broken;
        }

        /**
         * 从一轮请求算出指纹。
         *
         * @param systemPrompt system prompt，可为 {@code null}
         * @param tools        工具清单，不可为 {@code null}
         * @param messages     消息列表，不可为 {@code null}
         * @return 指纹（{@code broken} 恒为 {@code false}，由比较结果另定）
         */
        private static Snapshot of(String systemPrompt, List<LlmTool> tools, List<LlmMessage> messages) {
            List<Integer> hashes = new ArrayList<Integer>(messages.size());
            for (LlmMessage message : messages) {
                hashes.add(fingerprint(message).hashCode());
            }
            String prompt = systemPrompt == null ? "" : systemPrompt;
            return new Snapshot(prompt.hashCode(), prompt.length(), toolsFingerprint(tools).hashCode(),
                    Collections.unmodifiableList(hashes), false);
        }

        /**
         * 派生一份只改 {@code broken} 的副本。
         *
         * @param broken 是否断裂
         * @return 新指纹
         */
        private Snapshot withBroken(boolean broken) {
            if (this.broken == broken) {
                return this;
            }
            return new Snapshot(systemPromptHash, systemPromptLength, toolsHash, messageHashes, broken);
        }

        /**
         * 算一条消息的规范形态。
         * <p>
         * <b>为什么不用 {@code toString}</b>：那取决于各厂商实现，改一个字段名就会让「消息没变」
         * 被误报成「变了」——而一个会误报的观察器比没有观察器更糟，它会让人去查不存在的问题。
         * 字段用 {@code \u0000} 分隔，避免相邻字段拼接后被读成同一段。
         *
         * @param message 消息，不可为 {@code null}
         * @return 规范文本
         */
        private static String fingerprint(LlmMessage message) {
            StringBuilder text = new StringBuilder();
            text.append(message.getRole()).append('\u0000').append(nvl(message.getContent()));
            text.append('\u0000').append(nvl(message.getToolCallId()));
            text.append('\u0000').append(nvl(message.getName()));
            for (LlmToolCall call : message.getToolCalls()) {
                text.append('\u0000').append(nvl(call.getId()));
                text.append('\u0000').append(nvl(call.getName()));
                text.append('\u0000').append(nvl(call.getArguments()));
            }
            return text.toString();
        }

        /**
         * 算工具清单的规范形态：名称、描述、参数、必填项与顺序都要算进去——
         * 只比名字的话，「改了某个工具的参数」这种断裂会被漏报，而它同样作废整段前缀。
         *
         * @param tools 工具清单，不可为 {@code null}
         * @return 规范文本
         */
        private static String toolsFingerprint(List<LlmTool> tools) {
            StringBuilder text = new StringBuilder();
            for (LlmTool tool : tools) {
                text.append(tool.getName()).append('\u0000').append(nvl(tool.getDescription()));
                text.append('\u0000').append(tool.getParameters()).append('\u0000')
                        .append(tool.getRequired()).append('\u0001');
            }
            return text.toString();
        }

        /**
         * 把 {@code null} 当空串。
         *
         * @param value 值，可为 {@code null}
         * @return 非空字符串
         */
        private static String nvl(String value) {
            return value == null ? "" : value;
        }
    }

    /**
     * 一次观察的结论。不可变，可安全跨线程传递。
     *
     * @author zcd
     */
    public static final class Report {

        /** 是否首次观察这个会话（上一轮没有留档）。 */
        private final boolean firstObservation;

        /** system prompt 是否变了。 */
        private final boolean systemPromptChanged;

        /** system prompt 的长度变化，用于提示「变了多少」。 */
        private final int systemPromptLengthDelta;

        /** 工具清单是否变了。 */
        private final boolean toolsChanged;

        /** 消息序列是否被改写（不是单纯在尾部追加）。 */
        private final boolean historyRewritten;

        /** 与上一轮逐条相同的消息数。 */
        private final int reusableMessages;

        /** 上一轮的消息数。 */
        private final int previousMessages;

        /** 本轮的消息数。 */
        private final int currentMessages;

        /** 是否值得记一条 WARN：本次是这一轮断裂的第一次。 */
        private final boolean firstWarning;

        /**
         * 构造结论。
         *
         * @param firstObservation        是否首次观察
         * @param systemPromptChanged     system prompt 是否变了
         * @param systemPromptLengthDelta system prompt 长度变化
         * @param toolsChanged            工具清单是否变了
         * @param historyRewritten        消息序列是否被改写
         * @param reusableMessages        可复用消息数
         * @param previousMessages        上一轮消息数
         * @param currentMessages         本轮消息数
         * @param firstWarning            是否本轮的首次断裂
         */
        private Report(boolean firstObservation, boolean systemPromptChanged, int systemPromptLengthDelta,
                       boolean toolsChanged, boolean historyRewritten, int reusableMessages,
                       int previousMessages, int currentMessages, boolean firstWarning) {
            this.firstObservation = firstObservation;
            this.systemPromptChanged = systemPromptChanged;
            this.systemPromptLengthDelta = systemPromptLengthDelta;
            this.toolsChanged = toolsChanged;
            this.historyRewritten = historyRewritten;
            this.reusableMessages = reusableMessages;
            this.previousMessages = previousMessages;
            this.currentMessages = currentMessages;
            this.firstWarning = firstWarning;
        }

        /**
         * 比较两轮指纹。
         *
         * @param previous       上一轮指纹，可为 {@code null}（首次观察）
         * @param current        本轮指纹
         * @param previousBroken 上一轮是否已经处于断裂状态，用于告警节流
         * @return 结论
         */
        private static Report between(Snapshot previous, Snapshot current, boolean previousBroken) {
            if (previous == null) {
                return new Report(true, false, 0, false, false, 0, 0, current.messageHashes.size(), false);
            }
            boolean promptChanged = previous.systemPromptHash != current.systemPromptHash;
            boolean toolsChanged = previous.toolsHash != current.toolsHash;
            int reusable = commonPrefix(previous.messageHashes, current.messageHashes);
            // 「改写」有两种：前面某条内容变了，或整段变短了。单纯追加不算——那是缓存唯一能一直命中的形态
            boolean rewritten = reusable < previous.messageHashes.size()
                    || current.messageHashes.size() < previous.messageHashes.size();
            boolean broken = promptChanged || toolsChanged || rewritten;
            int delta = current.systemPromptLength - previous.systemPromptLength;
            return new Report(false, promptChanged, delta, toolsChanged, rewritten, reusable,
                    previous.messageHashes.size(), current.messageHashes.size(), broken && !previousBroken);
        }

        /**
         * 数两个序列从头开始相同的条数。
         *
         * @param before 前一个序列
         * @param after  后一个序列
         * @return 相同的前缀长度
         */
        private static int commonPrefix(List<Integer> before, List<Integer> after) {
            int limit = Math.min(before.size(), after.size());
            int index = 0;
            while (index < limit && before.get(index).equals(after.get(index))) {
                index++;
            }
            return index;
        }

        /**
         * 取断裂起始层。
         * <p>
         * <b>只报第一个断点</b>：断裂是累积的，第 0 个 token 变了，它后面的内容再变不变都无所谓，
         * 整段请求本来就要重算。因此缓存的真实语义只有一个断点。
         *
         * @return 断裂起始层；{@link #isBroken()} 为 {@code false} 时返回 {@code null}
         */
        CachePrefixChangedEvent.Layer firstBrokenLayer() {
            if (systemPromptChanged) {
                return CachePrefixChangedEvent.Layer.SYSTEM_PROMPT;
            }
            if (toolsChanged) {
                return CachePrefixChangedEvent.Layer.TOOLS;
            }
            return historyRewritten ? CachePrefixChangedEvent.Layer.HISTORY : null;
        }

        /**
         * 判断这一轮是否发生了断裂。
         *
         * @return 断裂返回 {@code true}
         */
        public boolean isBroken() {
            return systemPromptChanged || toolsChanged || historyRewritten;
        }

        /**
         * 判断这是不是该会话的第一次观察（没有可比对的上一轮）。
         *
         * @return 首次观察返回 {@code true}
         */
        public boolean isFirstObservation() {
            return firstObservation;
        }

        /**
         * 判断是否值得记一条 WARN。
         * <p>
         * 断裂持续期间只告警一次：逐轮 WARN 会把日志刷满，而「它一直在断」与「它刚开始断」
         * 需要的关注度完全不同。
         *
         * @return 值得告警返回 {@code true}
         */
        public boolean isFirstWarning() {
            return firstWarning;
        }

        /**
         * 生成一句话结论，供日志与诊断使用。
         *
         * @return 描述文本
         */
        public String describe() {
            if (firstObservation) {
                return "首次观察，无基线（消息 " + currentMessages + " 条）";
            }
            if (!isBroken()) {
                return "前缀可复用 " + reusableMessages + " 条，尾部追加 " + (currentMessages - reusableMessages)
                        + " 条";
            }
            StringBuilder text = new StringBuilder();
            if (systemPromptChanged) {
                text.append("system prompt 变了（").append(signed(systemPromptLengthDelta))
                        .append(" 字符）→ 整段请求失效");
            }
            if (toolsChanged) {
                appendSeparator(text).append("工具清单变了 → 整段请求失效");
            }
            if (historyRewritten) {
                appendSeparator(text).append("历史被改写：可复用 ").append(reusableMessages)
                        .append('/').append(previousMessages).append(" 条，第 ")
                        .append(reusableMessages + 1).append(" 条起全部失效");
            }
            return text.toString();
        }

        /**
         * 追加分隔符（只有前面已经有内容时才加）。
         *
         * @param text 目标缓冲
         * @return 目标缓冲
         */
        private static StringBuilder appendSeparator(StringBuilder text) {
            return text.length() == 0 ? text : text.append("；");
        }

        /**
         * 把变化量渲染成带符号的字符串。
         *
         * @param delta 变化量
         * @return 形如 {@code +12} 或 {@code -3}
         */
        private static String signed(int delta) {
            return delta >= 0 ? "+" + delta : Integer.toString(delta);
        }

        @Override
        public String toString() {
            return "CacheBreakWatcher.Report{broken=" + isBroken() + ", " + describe() + '}';
        }
    }
}
