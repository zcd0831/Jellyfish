package zcd.jellyfish.core.prompt;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.extension.AgingStrategy;
import zcd.jellyfish.api.extension.AgingStrategyRequest;
import zcd.jellyfish.infra.config.ReactSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.extension.HandlerBinding;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.tooloutput.ToolOutputEnvelope;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具结果老化：把「发往模型的请求」里较早的大结果替换成一行 stub。
 * <p>
 * <b>只改本次请求</b>：返回的是新的消息列表，{@code Session} 里存的历史一条不动——与
 * {@link ContextWindow} 同一口径。屏幕投影、持久化、{@code /resume} 看到的仍是完整结果，
 * 只有发给模型的那条链路变短了。
 * <p>
 * <b>为什么需要它</b>：机械裁剪（{@link ContextWindow}）只在总量超预算时才动手，而工具结果
 * 的特点是「单条就很大、还很早」——几十轮前读过的一个大文件，会一直占着上下文，直到某一次
 * 装不下被整组丢掉，那时连「曾经读过它、内容在哪」都不知道了。提前把旧大结果换成带路径的 stub，
 * 换回来的是「最近几轮仍然完整 + 更早的仍可回查」。
 * <p>
 * <b>只动截断信封，不动普通结果</b>：只有超限落盘过的结果才有信封。小结果本来就不占地方，
 * 把它们也换成 stub 只会让模型无端失忆。
 * <p>
 * <b>两种触发口径</b>，由 {@code react.cache.agingPercent} 选择：
 * <ul>
 *     <li>{@code 0}（缺省）——<b>按距尾部的条数</b>：保留最近 {@code keepRecentMessages} 条，
 *     更早的信封全部换成 stub。边界每轮重算；</li>
 *     <li>{@code &gt; 0}——<b>按上下文用量水位</b>：只在用量达到该百分比时老化，并且
 *     <b>一个压缩周期内只推进一次</b>，其余轮次的边界冻住不动。</li>
 * </ul>
 * <b>为什么水位口径要「冻住」而不是「每轮算一次」</b>：这是本类最重要的一条。厂商的 prompt 缓存
 * 是前缀匹配，命中部分按约 0.1× 计价，因此「删掉前缀中的 N 个 token」省下的是 N，却让它后面
 * 的 M 个 token 全部从 0.1× 变回 1×——只要 {@code M > 0.11N} 就是净亏（推导见
 * {@code docs/design/llm-cache.md} 的 R2）。而按条数口径下，每轮追加 2–3 条消息就让边界前移
 * 2–3 条，**恰好移过最近、最贵、刚刚才被缓存的那一段**，于是真正的大内容永远落在变动区、
 * 永远不被复用。改成水位触发后，边界在一个压缩周期内是函数意义上的常量，
 * 已发出去的前缀因此保持 append-only。
 * <p>
 * <b>水位口径为什么只推进一次就冻住</b>：若每轮都按「用量超了就再往前挪」推进，只要老化腾出的
 * 空间不够（例如最近那几条本身就很大），边界就会每轮继续前移，退化成原来的行为。以「压缩周期」
 * 为界——压过一次就重新累计——既拿到了「至多一次前缀断裂」的硬保证，也不至于让老化彻底失效：
 * 老化腾不出空间时，机械裁剪会接手，而它会立刻触发压缩。
 * <p>
 * <b>状态按会话有界</b>：只保留最近 {@value #MAX_SESSIONS} 个会话的边界，按访问顺序淘汰。
 * 淘汰的代价是「这个会话多断一次」，而不是算错——边界丢了就是从零重新累计。
 * <p>
 * <b>两个数量阈值与 stub 文案可以向插件要</b>（见 {@link AgingStrategyRequest}）：插件优先于
 * {@code react} 配置，内核只负责钳制与执行。而<b>边界算法本身不出内核</b>：它是前缀不变量的守卫，
 * 而前缀不变量是全局性质，改坏它就等于每轮都白花一次钱。
 * <p>
 * 线程安全：{@code react} 池线程会并发组装不同会话，因此边界状态的读写全部同步。
 *
 * @author zcd
 */
@Singleton
public class ToolResultAger {

    /** 最多同时记录多少个会话的老化边界。 */
    private static final int MAX_SESSIONS = 64;

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ToolResultAger.class);

    /** 运行时配置门面：各项参数现读，热更新后下一轮生效。 */
    private final RuntimeConfig runtimeConfig;

    /** 扩展注册表：向插件询问本次的老化策略。 */
    private final ExtensionRegistry extensions;

    /** 会话标识 → 该会话当前压缩周期内的老化边界，按访问顺序淘汰。 */
    private final Map<String, Frontier> frontiers = new LruFrontiers();

    /**
     * 构造老化器。
     *
     * @param runtimeConfig 运行时配置门面，不可为 {@code null}
     * @param extensions    扩展注册表，不可为 {@code null}
     */
    @Inject
    public ToolResultAger(RuntimeConfig runtimeConfig, ExtensionRegistry extensions) {
        this.runtimeConfig = runtimeConfig;
        this.extensions = extensions;
    }

    /**
     * 把较早的工具结果信封替换成 stub；更近的保留完整，非信封结果一律不动。
     *
     * @param sessionId 会话标识；{@code null} 表示无从跨轮记录边界，退化成只看本轮用量
     * @param boundary  当前的压缩边界下标，作为「压缩周期」的标识；边界一变即重新累计
     * @param usage     老化<b>之前</b>的上下文用量，衡量「若原样发出去会占多少」
     * @param messages  待发往模型的消息列表，可为 {@code null}
     * @return 老化后的消息列表；没有可老化的内容时返回原列表
     */
    public List<LlmMessage> age(String sessionId, int boundary, ContextUsage usage, List<LlmMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages == null ? Collections.<LlmMessage>emptyList() : messages;
        }
        ReactSettings settings = runtimeConfig.getReactSettings();
        AgingStrategy strategy = strategyOf(sessionId, boundary, usage, messages.size(), settings);
        int keepRecent = keepRecentOf(strategy, messages.size(), settings);
        if (keepRecent <= 0) {
            // 0 是总开关：既表示「不裁剪」，也表示「不做老化」。
            // 水位口径也要求保留窗口，否则「老化到哪为止」无从回答
            return messages;
        }
        int agingPercent = agingPercentOf(strategy, settings);
        if (agingPercent <= 0) {
            return stub(messages, targetOf(messages.size(), keepRecent), strategy);
        }
        return stub(messages, frontierOf(sessionId, boundary, messages.size(), keepRecent,
                usage.exceeds(agingPercent)), strategy);
    }

    /**
     * 询问各插件本次的老化策略，按 {@code order} 升序逐字段取第一个非空。
     * <p>
     * <b>处理器失败只记告警并跳过</b>：老化是上下文治理的一环，但一个坏插件不该让整轮对话发不出去。
     *
     * @param sessionId  会话标识，可为 {@code null}
     * @param boundary   当前压缩边界下标
     * @param usage      老化前的上下文用量
     * @param messageCount 本次可见的消息条数
     * @param settings   运行时配置（提供缺省值）
     * @return 合并后的策略，保证非 {@code null}
     */
    private AgingStrategy strategyOf(String sessionId, int boundary, ContextUsage usage, int messageCount,
                                     ReactSettings settings) {
        List<HandlerBinding<AgingStrategyRequest, AgingStrategy>> bindings =
                extensions.bindings(AgingStrategyRequest.class, null);
        if (bindings.isEmpty()) {
            return AgingStrategy.none();
        }
        AgingStrategyRequest request = new AgingStrategyRequest(sessionId, messageCount, usage.getUsedTokens(),
                usage.getBudgetTokens(), boundary, settings.getToolOutput().getKeepRecentMessages(),
                settings.getCache().getAgingPercent());
        Integer keepRecent = null;
        Integer agingPercent = null;
        String stubText = null;
        Map<String, String> stubTextsByTool = null;
        for (HandlerBinding<AgingStrategyRequest, AgingStrategy> binding : bindings) {
            AgingStrategy strategy = strategyOf(binding, request);
            if (strategy == null) {
                continue;
            }
            if (keepRecent == null && strategy.getKeepRecentMessages() != null) {
                keepRecent = strategy.getKeepRecentMessages();
            }
            if (agingPercent == null && strategy.getAgingPercent() != null) {
                agingPercent = strategy.getAgingPercent();
            }
            if (stubText == null && StringUtils.isNotBlank(strategy.getStubText())) {
                stubText = strategy.getStubText().trim();
            }
            if ((stubTextsByTool == null || stubTextsByTool.isEmpty())
                    && !strategy.getStubTextsByTool().isEmpty()) {
                stubTextsByTool = strategy.getStubTextsByTool();
            }
        }
        return new AgingStrategy(keepRecent, agingPercent, stubText, stubTextsByTool);
    }

    /**
     * 调用一个老化策略处理器，把异常折成 {@code null}。
     *
     * @param binding 处理器绑定
     * @param request 请求载荷
     * @return 处理器结果；它失败时返回 {@code null}
     */
    private AgingStrategy strategyOf(HandlerBinding<AgingStrategyRequest, AgingStrategy> binding,
                                     AgingStrategyRequest request) {
        try {
            return extensions.invoke(binding.getHandler(), request);
        } catch (Exception e) {
            LOG.warn("老化策略处理器执行失败，已跳过: owner={} reason={}", binding.getOwner(), e.getMessage());
            return null;
        }
    }

    /**
     * 取生效的保留条数：插件策略优先于配置，并钳制到合法区间。
     *
     * @param strategy    插件策略
     * @param messageCount 消息条数
     * @param settings    运行时配置
     * @return 保留条数，落在 {@code [0, messageCount]}
     */
    private static int keepRecentOf(AgingStrategy strategy, int messageCount, ReactSettings settings) {
        Integer declared = strategy.getKeepRecentMessages();
        int keepRecent = declared == null ? settings.getToolOutput().getKeepRecentMessages() : declared;
        return Math.max(0, Math.min(keepRecent, messageCount));
    }

    /**
     * 取生效的老化水位：插件策略优先于配置，并钳制到合法区间。
     *
     * @param strategy 插件策略
     * @param settings 运行时配置
     * @return 百分比，落在 {@code [0, 100]}
     */
    private static int agingPercentOf(AgingStrategy strategy, ReactSettings settings) {
        Integer declared = strategy.getAgingPercent();
        int percent = declared == null ? settings.getCache().getAgingPercent() : declared;
        return Math.max(0, Math.min(percent, 100));
    }

    /**
     * 解析本次要老化到哪个下标（不含），必要时推进并记住边界。
     * <p>
     * <b>推进条件是「跨越」而不是「高于」</b>：进入本方法的前提是使用方已判断用量达到了水位，
     * 而这里额外要求「本压缩周期还没推进过」。因此水位之上停留再久也不会继续前移——
     * 那正是按条数口径每轮破坏缓存的原因。
     *
     * @param sessionId  会话标识，可为 {@code null}
     * @param boundary   当前压缩边界下标
     * @param size       本次可见的消息条数
     * @param keepRecent 保留完整内容的最近消息条数
     * @param crossed    自适应用量是否达到水位线
     * @return 要替换成 stub 的下标上界（不含）
     */
    private synchronized int frontierOf(String sessionId, int boundary, int size, int keepRecent,
                                        boolean crossed) {
        int target = targetOf(size, keepRecent);
        if (sessionId == null) {
            // 没有键就无从归属，也无从跨轮保持：退化成「只看本轮用量」。
            // 这比编一个共享键更安全——共用键会让两个会话互相推进对方的边界
            return crossed ? target : 0;
        }
        Frontier frontier = frontiers.get(sessionId);
        if (frontier == null || frontier.getBoundary() != boundary) {
            // 新会话，或刚压缩过：边界变了，前缀反正要断，因此从零重新累计
            frontier = new Frontier(boundary);
            frontiers.put(sessionId, frontier);
        }
        if (crossed && !frontier.isUsed()) {
            frontier.use(target);
        }
        return frontier.getAgedUpTo();
    }

    /**
     * 计算「保留最近 {@code keepRecent} 条」对应的老化边界。
     *
     * @param size       消息条数
     * @param keepRecent 保留的最近消息条数
     * @return 边界下标上界（不含），不会小于 {@code 0}
     */
    private static int targetOf(int size, int keepRecent) {
        return Math.max(0, size - keepRecent);
    }

    /**
     * 把 {@code [0, frontier)} 范围内的工具结果信封替换成 stub。
     *
     * @param messages  待发往模型的消息列表
     * @param frontier  边界下标上界（不含）
     * @param strategy  本次生效的老化策略（提供 stub 文案）
     * @return 老化后的列表；没有任何内容被替换时返回原列表
     */
    private static List<LlmMessage> stub(List<LlmMessage> messages, int frontier, AgingStrategy strategy) {
        List<LlmMessage> aged = null;
        for (int index = 0; index < frontier; index++) {
            LlmMessage message = messages.get(index);
            if (!LlmMessage.ROLE_TOOL.equals(message.getRole())) {
                continue;
            }
            ToolOutputEnvelope envelope = ToolOutputEnvelope.parse(message.getContent());
            if (envelope == null) {
                continue;
            }
            if (aged == null) {
                aged = new ArrayList<LlmMessage>(messages);
            }
            aged.set(index, new LlmMessage(message.getRole(), envelope.stub(stubTemplateOf(strategy, envelope)),
                    message.getToolCallId(), message.getName(), null));
        }
        return aged == null ? messages : Collections.unmodifiableList(aged);
    }

    /**
     * 取某个信封该用的 stub 模板：按工具名的覆盖优先于通用模板。
     * <p>
     * <b>为什么要有「按工具名」这一层</b>：同一条 stub 文案对「读文件」与「跑命令」的贴切程度不一样，
     * 而写工具插件的作者同时知道自己那几个工具的名字与它们的输出形态。只给一个通用模板，
     * 插件就只能挑一个折中的措辞——那正是本扩展点想避免的事。
     *
     * @param strategy 本次生效的策略
     * @param envelope 被老化的信封
     * @return 模板；{@code null} 表示用内核缺省
     */
    private static String stubTemplateOf(AgingStrategy strategy, ToolOutputEnvelope envelope) {
        String byTool = strategy.getStubTextsByTool().get(envelope.getToolName());
        if (StringUtils.isNotBlank(byTool)) {
            return byTool;
        }
        return strategy.getStubText();
    }

    /**
     * 一个会话在某个压缩周期内的老化边界。
     * <p>
     * 不可变的是 {@link #getBoundary()}；{@link #getAgedUpTo()} 在周期内只会被设置一次，
     * 因此对调用方而言它在一个周期内是常量，前缀得以保持 append-only。
     */
    private static final class Frontier {

        /** 建立本边界时生效的压缩边界下标；它与当前值不同即说明周期已翻页。 */
        private final int boundary;

        /** 要替换成 stub 的下标上界（不含），周期内一旦确定不再改变。 */
        private int agedUpTo;

        /** 本周期是否已经推进过一次。 */
        private boolean used;

        /**
         * 构造边界。
         *
         * @param boundary 压缩边界下标
         */
        Frontier(int boundary) {
            this.boundary = boundary;
        }

        /**
         * 获取建立时的压缩边界下标。
         *
         * @return 压缩边界下标
         */
        int getBoundary() {
            return boundary;
        }

        /**
         * 获取老化边界。
         *
         * @return 下标上界（不含）
         */
        int getAgedUpTo() {
            return agedUpTo;
        }

        /**
         * 判断本周期是否已推进过。
         *
         * @return 已推进返回 {@code true}
         */
        boolean isUsed() {
            return used;
        }

        /**
         * 推进老化边界（周期内只应调用一次）。
         *
         * @param agedUpTo 下标上界（不含）
         */
        void use(int agedUpTo) {
            this.used = true;
            this.agedUpTo = agedUpTo;
        }
    }

    /**
     * 按访问顺序淘汰的会话边界表。
     */
    private static final class LruFrontiers extends LinkedHashMap<String, Frontier> {

        /** 序列化标识（本类从不序列化，仅为满足 Serializable 约定）。 */
        private static final long serialVersionUID = 1L;

        /**
         * 构造按访问顺序排序的映射。
         */
        LruFrontiers() {
            super(16, 0.75f, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Frontier> eldest) {
            return size() > MAX_SESSIONS;
        }
    }
}
