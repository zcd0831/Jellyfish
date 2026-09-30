package zcd.jellyfish.core.prompt;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.extension.PromptContribution;
import zcd.jellyfish.api.extension.PromptContributionRequest;
import zcd.jellyfish.api.extension.PromptPlacement;
import zcd.jellyfish.api.extension.TurnContext;
import zcd.jellyfish.api.extension.TurnContextRequest;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.ReactSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.extension.HandlerBinding;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmRequest;
import zcd.jellyfish.infra.llm.LlmTool;
import zcd.jellyfish.infra.model.ResolvedModel;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionCompaction;
import zcd.jellyfish.infra.session.SessionMessage;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 提示词与上下文组装：把一次会话状态落成一份发给厂商的 {@link LlmRequest}。
 * <p>
 * 组装四件事，按固定顺序：
 * <ol>
 *     <li><b>system prompt</b>：agent 提示词原文 + 各插件贡献块；两者都为空则不下发；</li>
 *     <li><b>消息历史</b>：先跳过已被压缩摘要覆盖的那一段，再按模型上下文窗口做机械裁剪
 *     （只裁本次请求，不写回会话），最后把压缩摘要作为一条<b>合成消息</b>放在最前；</li>
 *     <li><b>工具清单</b>：来自 {@link ToolCatalog}，与注册表同源；</li>
 *     <li><b>采样参数</b>：模型名取 {@code Model.getId()}、最大输出取 {@code Model.getMaxOutputTokens()}。</li>
 * </ol>
 * <b>system prompt 在一个会话里逐字节恒定</b>：它只由 agent 提示词与插件贡献块组成，两者都不随
 * 轮次变化。这不是巧合而是刻意维持的不变量——厂商的 prompt 缓存是前缀匹配，system prompt 站在
 * 第 0 个 token，它一变后面全部内容（连同整个历史）都要按未命中价重发。「system prompt 变了」
 * 因此可以当成一条 bug 信号来查，见 {@link CacheBreakWatcher}。
 * <p>
 * <b>插件上下文为什么拼进 system prompt 而不是消息列表</b>：它是「本轮的上下文注入」，不是对话历史。
 * 塞进 {@code messages} 会被后续 append 回会话，导致每轮重复累积、回放与 token 统计失真。
 * <b>但会随轮次变化的状态就不该走这里</b>——它会让上面那条不变量当场失效，而代价是整个请求。
 * 这类东西应走 {@link TurnContextRequest}（产物由 {@code ReActLooper} 拼进本轮用户消息并随消息落盘，
 * 因此是 append-only 的），或者干脆不进 prompt。
 * <p>
 * <b>压缩摘要为什么改成「合成消息」而不是留在 system prompt</b>：摘要只在压缩时变，留在 system
 * prompt 里并不会造成每轮断裂，因此这一改动<b>不带来命中率收益</b>——推导见
 * {@code docs/design/llm-cache.md} §4.3，那里论证了两种排法的分叉点是同一个位置。换来的是上述
 * 不变量，以及 Anthropic {@code cache_control} 的落点：断点应当打在稳定前缀的末尾，
 * 而 system prompt 的末尾此前恰好是会变的摘要。
 *
 * @author zcd
 */
@Singleton
public class PromptAssembler {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(PromptAssembler.class);

    /** 贡献块之间的分隔符。 */
    private static final String BLOCK_SEPARATOR = "\n\n";

    /**
     * 「模型没配上下文窗口」的预算哨兵。
     * <p>
     * 不用 {@code 0}：预算恰好为 {@code 0} 是「配了窗口但被输出与预留吃光」的真实情形，
     * 那种情况下仍需按 {@code 0} 预算裁剪，而不是当作「无从判断」原样下发。
     */
    private static final int NO_CONTEXT_WINDOW = -1;

    /**
     * 压缩 fork 请求的工具选择策略。
     * <p>
     * 不带工具会让前缀从工具那一段起全部作废，带上工具又可能让模型去调工具而不是写摘要，
     * 因此必须原样带工具 + 明确关掉工具调用。四家都支持该取值：OpenAI / DeepSeek 用
     * {@code "none"}、Gemini 映射为 {@code mode: NONE}、Anthropic 映射为 {@code {"type":"none"}}。
     */
    private static final String TOOL_CHOICE_NONE = "none";

    /** agent 提示词。 */
    private final AgentManager agentManager;

    /** 工具目录。 */
    private final ToolCatalog toolCatalog;

    /** ReAct 运行期参数，提供上下文预留。 */
    private final RuntimeConfig runtimeConfig;

    /** 同步扩展点策略，提示词贡献的唯一来源。 */
    private final ExtensionRegistry extensions;

    /** 工具结果老化器：较早的大结果在发往模型前换成 stub。 */
    private final ToolResultAger toolResultAger;

    /** 缓存断裂观察器：对比相邻两轮的可缓存前缀，断裂时记日志。 */
    private final CacheBreakWatcher cacheBreakWatcher;

    /**
     * 构造提示词组装器。
     *
     * @param agentManager  agent 门面
     * @param toolCatalog   工具目录
     * @param runtimeConfig 运行时配置门面（读取 ReAct 段的预留 token）
     * @param extensions    同步扩展点策略（取本轮提示词贡献）
     * @param toolResultAger 工具结果老化器（较早的大结果换成 stub）
     * @param cacheBreakWatcher 缓存断裂观察器（对比相邻两轮的可缓存前缀）
     */
    @Inject
    public PromptAssembler(AgentManager agentManager, ToolCatalog toolCatalog, RuntimeConfig runtimeConfig,
                           ExtensionRegistry extensions, ToolResultAger toolResultAger,
                           CacheBreakWatcher cacheBreakWatcher) {
        this.agentManager = Objects.requireNonNull(agentManager, "agentManager must not be null");
        this.toolCatalog = Objects.requireNonNull(toolCatalog, "toolCatalog must not be null");
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig must not be null");
        this.extensions = Objects.requireNonNull(extensions, "extensions must not be null");
        this.toolResultAger = Objects.requireNonNull(toolResultAger, "toolResultAger must not be null");
        this.cacheBreakWatcher = Objects.requireNonNull(cacheBreakWatcher,
                "cacheBreakWatcher must not be null");
    }

    /**
     * 构建一次 LLM 请求。
     * <p>
     * 只关心请求本身的调用点用它；需要据此判断「要不要压缩」的调用点用
     * {@link #assemble(Session, ResolvedModel)}，那个还会带回上下文用量。
     *
     * @param session       会话运行态，不可为 {@code null}
     * @param resolvedModel 已解析的模型，不可为 {@code null}
     * @return 统一请求模型
     */
    public LlmRequest buildRequest(Session session, ResolvedModel resolvedModel) {
        return assemble(session, resolvedModel).getRequest();
    }

    /**
     * 构建一次 LLM 请求，并带回它的上下文用量。
     * <p>
     * <b>用量与裁剪在同一次计算里产出</b>：{@code ContextWindow} 裁掉什么、system prompt 占多少，
     * 只有这里知道。让压缩的调用方另算一遍，就会出现两套会漂移的判据。
     *
     * @param session       会话运行态，不可为 {@code null}
     * @param resolvedModel 已解析的模型，不可为 {@code null}
     * @return 请求与用量
     */
    public PromptAssembly assemble(Session session, ResolvedModel resolvedModel) {
        return assemble(session, resolvedModel, ToolFilter.none());
    }

    /**
     * 构建一次 LLM 请求，带上工具清单过滤。
     * <p>
     * <b>只有嵌套回合会传非空过滤</b>（见 {@link ToolFilter}）：主会话路径始终走两参重载，
     * 行为与引入本重载之前完全一致。
     *
     * @param session       会话运行态，不可为 {@code null}
     * @param resolvedModel 已解析的模型，不可为 {@code null}
     * @param toolFilter    工具清单过滤器，不可为 {@code null}
     * @return 请求与用量
     */
    public PromptAssembly assemble(Session session, ResolvedModel resolvedModel, ToolFilter toolFilter) {
        Parent parent = parentOf(session, resolvedModel, toolFilter);
        return new PromptAssembly(parent.getRequest(), parent.getUsage());
    }

    /**
     * 构建一次「cache-safe fork」请求：与父请求共用<b>逐字节相同</b>的前缀，指令追加在最后。
     * <p>
     * <b>为什么这是压缩唯一值得做的省钱手段</b>：压缩的请求此前从第 0 个 token 就分叉（自己的指令做
     * system prompt、渲染后的全文做消息、不带工具），于是<b>那次调用把整段对话按未命中价重发一遍</b>，
     * 而它恰恰发生在会话最长的时候。改成取父请求的真前缀之后，整段对话按命中价（约 0.1×）计费，
     * 只有末尾那条指令是新的。
     * <p>
     * <b>为什么必须切父请求的字节，而不能按会话下标重新装配</b>：机械裁剪会从最旧侧吞掉若干条，
     * 重装一次拿到的前端就可能与父请求不同；缓存是逐位置比的，前端一错就全部落空。
     * 因此这里只做一件事：把父请求的消息列表去掉末尾「本来就要保留的 {@code keepCount} 条」。
     * <p>
     * <b>工具要原样带上</b>：工具清单在多数厂商的模板里排在 messages 之前，省掉它等于把整条前缀
     * 从工具那一段起全部作废。带上之后必须把工具调用关掉（{@code tool_choice: none}），
     * 否则模型很可能去调工具而不是写摘要。
     *
     * @param session       会话运行态，不可为 {@code null}
     * @param resolvedModel 已解析的模型，不可为 {@code null}
     * @param fromIndex     待压范围的第一条消息对应的会话下标
     * @param keepCount     父请求末尾要丢掉的消息条数（即将来保留原文的那一段）
     * @param instruction   追加在末尾的指令，不可为空白
     * @return fork 请求；待压范围已不在父请求里时返回 {@code null}，由调用方回退到旧路径
     */
    public LlmRequest buildFork(Session session, ResolvedModel resolvedModel, int fromIndex, int keepCount,
                                String instruction) {
        Parent parent = parentOf(session, resolvedModel, ToolFilter.none());
        LlmRequest base = parent.getRequest();
        if (parent.getFirstSessionIndex() < 0 || parent.getFirstSessionIndex() > fromIndex) {
            // 待压的那一段已经不在父请求里（机械裁剪从最旧侧把它吞掉了）。此时 fork 出来的前缀会
            // 缺一段内容，摘要将毫无依据；退回旧路径把该段渲染成正文发出去，按全价但正确。
            // 实际情况是本就不该压：那一段根本没发给模型，推进边界只会白白换来一次前缀断裂
            LOG.warn("待压范围已不在父请求中，压缩回退到旧路径: sessionId={} from={} parentStart={}",
                    session.getSessionId(), fromIndex, parent.getFirstSessionIndex());
            return null;
        }
        List<LlmMessage> messages = base.getMessages();
        List<LlmMessage> forked = new ArrayList<LlmMessage>(
                messages.subList(0, Math.max(0, messages.size() - keepCount)));
        forked.add(LlmMessage.user(instruction));
        LlmRequest.Builder builder = LlmRequest.builder(base.getModel())
                .systemPrompt(base.getSystemPrompt())
                .messages(forked)
                .tools(base.getTools())
                .toolChoice(TOOL_CHOICE_NONE);
        if (base.getMaxTokens() != null) {
            builder.maxTokens(base.getMaxTokens());
        }
        return builder.build();
    }

    /**
     * 装配一次「父请求」，并带回「发出的第一条历史消息对应哪个会话下标」。
     * <p>
     * 那个下标是给 {@link #buildFork} 用的，理由见那里：cache-safe fork 只能切父请求自己的字节，
     * 因此得知道父请求的消息列表对应会话的哪一段。
     *
     * @param session       会话运行态，不可为 {@code null}
     * @param resolvedModel 已解析的模型，不可为 {@code null}
     * @param toolFilter    工具清单过滤器，不可为 {@code null}
     * @return 装配产物，保证非 {@code null}
     */
    private Parent parentOf(Session session, ResolvedModel resolvedModel, ToolFilter toolFilter) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(resolvedModel, "resolvedModel must not be null");
        Objects.requireNonNull(toolFilter, "toolFilter must not be null");
        int boundary = effectiveBoundary(session);
        String systemPrompt = systemPromptOf(session);
        LlmMessage summary = summaryMessageOf(session, boundary);
        int start = startIndexOf(session, boundary + 1);
        List<LlmMessage> history = toLlmMessages(session, start);
        int budget = budgetOf(resolvedModel);
        // 老化仍排在裁剪之前：先把较早的大结果换成 stub，再让裁剪看到它真实的体积；
        // 反过来则会先把整组丢掉，连「内容在哪」都一起没了。
        // 但触发判据是「若原样发出去会占多少」，因此用量必须在老化<b>之前</b>算——
        // 顺序反过来会自我抵销：老化腾出空间 → 用量降下来 → 下一轮不老化 → 用量又涨上去，来回跳
        List<LlmMessage> aged = toolResultAger.age(session.getSessionId(), boundary,
                usageOf(systemPrompt, summary, history, budget), history);
        CropResult cropResult = crop(budget, systemPrompt, summary, aged);
        // 摘要拼在裁剪之后：它是「被裁掉的那段的精华」，让裁剪有机会丢掉它等于白压一次
        List<LlmMessage> messages = withSummary(summary, cropResult.getMessages());
        // 机械裁剪只从最旧侧整组丢弃，因此发出去的历史必是会话历史的一段后缀，
        // 「发出的第一条 ↔ 会话下标」这个换算才成立
        int sentHistory = cropResult.getMessages().size();
        int firstSessionIndex = sentHistory == 0 ? -1 : start + (aged.size() - sentHistory);
        List<LlmTool> tools = toolCatalog.tools(toolFilter);
        watchCacheBreak(session, systemPrompt, tools, messages);
        LlmRequest.Builder builder = LlmRequest.builder(resolvedModel.getModel().getId())
                .systemPrompt(systemPrompt)
                .messages(messages)
                .tools(tools);
        int maxOutputTokens = resolvedModel.getModel().getMaxOutputTokens();
        if (maxOutputTokens > 0) {
            builder.maxTokens(maxOutputTokens);
        }
        return new Parent(builder.build(),
                usageOf(systemPrompt, null, messages, cropResult.getBudget(), cropResult.isTruncated()),
                firstSessionIndex);
    }

    /**
     * 一次父请求的装配产物：请求本身、它的用量，以及「第一条历史消息对应的会话下标」。
     * <p>
     * {@code firstSessionIndex} 为 {@code -1} 表示本次没有发出任何历史消息。
     */
    private static final class Parent {

        /** 已装配的请求。 */
        private final LlmRequest request;

        /** 上下文用量。 */
        private final ContextUsage usage;

        /** 请求里第一条历史消息对应的会话下标；{@code -1} 表示没有历史。 */
        private final int firstSessionIndex;

        /**
         * 构造装配产物。
         *
         * @param request           已装配的请求
         * @param usage             上下文用量
         * @param firstSessionIndex 第一条历史消息的会话下标；{@code -1} 表示没有历史
         */
        Parent(LlmRequest request, ContextUsage usage, int firstSessionIndex) {
            this.request = request;
            this.usage = usage;
            this.firstSessionIndex = firstSessionIndex;
        }

        /**
         * 获取已装配的请求。
         *
         * @return 请求
         */
        LlmRequest getRequest() {
            return request;
        }

        /**
         * 获取上下文用量。
         *
         * @return 用量
         */
        ContextUsage getUsage() {
            return usage;
        }

        /**
         * 获取第一条历史消息的会话下标。
         *
         * @return 下标；{@code -1} 表示没有历史
         */
        int getFirstSessionIndex() {
            return firstSessionIndex;
        }
    }

    /**
     * 计算一次请求的上下文用量。
     * <p>
     * <b>为什么老化前也算一次</b>：老化的触发判据与压缩一样要跟裁剪同源（同一个
     * {@link TokenEstimator}、同一个预算），否则三处会在边界情形给出互相矛盾的结论。
     * 老化那一次看的是「若原样发出去会占多少」，因此不含裁剪结果。
     *
     * @param systemPrompt system prompt，可为 {@code null}
     * @param summary      将要前置的摘要合成消息，可为 {@code null}
     * @param messages     本次要发送的消息
     * @param budget       可用预算，{@code <= 0} 表示无从判断
     * @return 用量
     */
    private static ContextUsage usageOf(String systemPrompt, LlmMessage summary, List<LlmMessage> messages,
                                       int budget) {
        return usageOf(systemPrompt, summary, messages, budget, false);
    }

    /**
     * 计算一次请求的上下文用量，并指定是否已发生机械裁剪。
     *
     * @param systemPrompt system prompt，可为 {@code null}
     * @param summary      将要前置的摘要合成消息，可为 {@code null}
     * @param messages     本次要发送的消息
     * @param budget       可用预算，{@code <= 0} 表示无从判断
     * @param truncated    是否已发生机械裁剪
     * @return 用量
     */
    private static ContextUsage usageOf(String systemPrompt, LlmMessage summary, List<LlmMessage> messages,
                                       int budget, boolean truncated) {
        int used = TokenEstimator.estimate(systemPrompt) + TokenEstimator.estimateMessage(summary)
                + TokenEstimator.estimateMessages(messages);
        return new ContextUsage(used, budget, truncated);
    }

    /**
     * 观察本轮的可缓存前缀，断裂时记一条日志。
     * <p>
     * <b>为什么在「实际要发送的消息」上观察，而不是在会话历史上</b>：缓存匹配的是<b>发出去的那一段</b>。
     * 老化与裁剪都发生在组装里，只有这里能看到它们对前缀做了什么——在会话历史上看到的「一切正常」，
     * 恰恰是缓存断裂最容易藏身的地方。
     * <p>
     * <b>为什么断裂只告警一次</b>：断裂当前是设计性的（见 {@code docs/design/llm-cache.md} 的 R1 / R2），
     * 逐轮 WARN 会把日志刷满，反而让「它是什么时候开始的」看不出来。恢复之后再次断裂会重新告警。
     *
     * @param session      会话运行态
     * @param systemPrompt 本轮的 system prompt，可为 {@code null}
     * @param tools        本轮的工具清单
     * @param messages     本轮实际要发送的消息
     */
    private void watchCacheBreak(Session session, String systemPrompt, List<LlmTool> tools,
                                 List<LlmMessage> messages) {
        CacheBreakWatcher.Report report =
                cacheBreakWatcher.observe(session.getSessionId(), systemPrompt, tools, messages);
        if (report.isFirstWarning()) {
            LOG.warn("可缓存前缀断裂: {} (sessionId={})", report.describe(), session.getSessionId());
        } else {
            LOG.debug("可缓存前缀: {} (sessionId={})", report.describe(), session.getSessionId());
        }
    }

    /**
     * 组装 system prompt：agent 提示词原文 + 各插件贡献块。
     * <p>
     * <b>不含压缩摘要，也不含任何随轮次变化的东西</b>：本方法的产物在同一个会话里必须逐字节恒定。
     * 摘要曾经拼在这里（排在贡献块之后），现在改走 {@link #summaryMessageOf} 进消息区。
     *
     * @param session 会话运行态
     * @return system prompt；两者都为空时返回 {@code null}（不下发）
     */
    public String systemPromptOf(Session session) {
        StringBuilder text = new StringBuilder();
        appendBlock(text, agentManager.systemPromptOf(session.getAgentId()));
        appendBlock(text, contributionsOf(session));
        return text.length() == 0 ? null : text.toString();
    }

    /**
     * 追加一个非空块，块与块之间用空行分隔。
     *
     * @param text  目标缓冲
     * @param block 块文本，可为 {@code null}
     */
    private static void appendBlock(StringBuilder text, String block) {
        if (StringUtils.isBlank(block)) {
            return;
        }
        if (text.length() > 0) {
            text.append(BLOCK_SEPARATOR);
        }
        text.append(block.trim());
    }

    /**
     * 把压缩摘要做成一条出站合成消息。
     * <p>
     * <b>为什么是「每次现算」而不是落盘</b>：它一旦被 append 回会话，就会每轮重复累积，
     * 越聊越像一份不断膨胀的假历史；屏幕投影与 {@code /resume} 也会跟着多出一条谁都没说过的话。
     * 现算的代价只是一次字符串拼接。
     * <p>
     * <b>角色固定为 {@code user}，并且不单独成条时会并进紧随其后的 user 消息</b>：
     * 不能用 {@code system} 角色——那会被 Claude / Gemini 的 {@code collectSystemPrompt}
     * 上提回顶层 system prompt，等于什么都没搬；而 Anthropic 又拒绝连续的 {@code user} 消息
     * （见 {@code ClaudeLlmClient.buildMessages} 合并工具结果那段）。因此
     * {@link #withSummary} 在必要时代为合并，见那里的说明。
     * <p>
     * <b>为什么带「已压缩 N 条」的抬头</b>：模型无从知道自己的历史被截过，它会自然地假设
     * 「我没看到的就是没发生过」。把这件事讲明，它才会在需要细节时去查文件而不是凭印象编。
     * <p>
     * <b>被丢弃的那部分要单独说</b>：压缩时受摘要输入预算限制、既没进摘要也不再发送的消息，
     * 是真正消失的数据。抬头若只说「已压缩」，模型会以为摘要里已经涵盖了它们——那比不知道更糟，
     * 因为它会拿一份缺了内容的摘要当作完整的过往。
     *
     * @param session  会话运行态
     * @param boundary 有效的压缩边界下标，{@code -1} 表示没有有效压缩
     * @return 摘要消息；没有有效压缩时返回 {@code null}
     */
    private static LlmMessage summaryMessageOf(Session session, int boundary) {
        if (boundary < 0) {
            return null;
        }
        SessionCompaction compaction = session.getCompaction();
        if (compaction == null) {
            return null;
        }
        StringBuilder text = new StringBuilder("[历史摘要] 更早的 ").append(boundary + 1)
                .append(" 条消息已不在上下文中");
        int dropped = compaction.getDroppedMessageCount();
        if (dropped > 0) {
            text.append("（其中 ").append(dropped).append(" 条因超出摘要预算未被收录）");
        }
        return LlmMessage.user(text.append("。摘要如下：\n").append(compaction.getSummary()).toString());
    }

    /**
     * 把摘要消息拼到已裁剪历史的最前面，必要时与紧随其后的 user 消息合并。
     * <p>
     * <b>为什么需要合并这一步</b>：Anthropic 拒绝连续的 {@code user} 消息。压缩边界之后的第一条消息
     * 既可能是 user（一个回合的开头）也可能是 assistant（工具调用组），前者占多数——若摘要与它各占一条，
     * 请求会被直接拒掉。合并后仍是一条 user 消息，内容语义也没有损失：摘要本就是给模型的背景补充。
     * <p>
     * <b>只改出站内容，不动会话</b>：与 {@code ToolResultAger} 在发送期改写工具结果同一口径，
     * 屏幕投影、落盘与 {@code /resume} 看到的仍是原始消息。
     *
     * @param summary  摘要消息，可为 {@code null}
     * @param messages 已裁剪的历史消息，不可为 {@code null}
     * @return 拼好摘要的消息列表
     */
    private static List<LlmMessage> withSummary(LlmMessage summary, List<LlmMessage> messages) {
        if (summary == null) {
            return messages;
        }
        List<LlmMessage> merged = new ArrayList<LlmMessage>(messages.size() + 1);
        if (!messages.isEmpty() && LlmMessage.ROLE_USER.equals(messages.get(0).getRole())) {
            merged.add(LlmMessage.user(summary.getContent() + BLOCK_SEPARATOR
                    + StringUtils.defaultString(messages.get(0).getContent())));
            merged.addAll(messages.subList(1, messages.size()));
            return merged;
        }
        merged.add(summary);
        merged.addAll(messages);
        return merged;
    }

    /**
     * 解析有效的压缩边界下标。
     * <p>
     * <b>边界消息缺失时按「未压缩」处理</b>：这只会发生在手工改过会话文件的情况下。此时若仍然
     * 注入摘要又发送全部消息，模型会同时拿到「旧摘要」与「被它覆盖的原文」，而两者的时间关系无从判断；
     * 退回未压缩（多发一些历史）只是贵一点，不会让模型基于矛盾的信息作答。
     *
     * @param session 会话运行态
     * @return 边界下标；没有压缩或边界消息不在会话里时返回 {@code -1}
     */
    private static int effectiveBoundary(Session session) {
        SessionCompaction compaction = session.getCompaction();
        if (compaction == null) {
            return -1;
        }
        int boundary = session.indexOfMessage(compaction.getBoundaryMessageId());
        if (boundary < 0) {
            LOG.warn("压缩边界消息不在会话里，按未压缩处理: sessionId={} boundary={}",
                    session.getSessionId(), compaction.getBoundaryMessageId());
        }
        return boundary;
    }

    /**
     * 询问各插件本轮要贡献的上下文，按「分层 → 注册顺序」拼接。
     * <p>
     * <b>单个处理器失败只记告警并跳过</b>：贡献是「锦上添花」的上下文，一个坏插件不该让整个对话
     * 发不出去——这与「工具失败转成 tool 结果」是同一种取舍，只有模型调用本身失败才上抛。
     * <p>
     * <b>先按分层再按注册顺序</b>：注册表给的顺序是 {@code order} 升序 + 注册顺序，
     * 在这里再按 {@link PromptPlacement} 分道——因为缓存是前缀匹配，块放在第几位直接决定了
     * 「它一变要作废多少内容」。同一分层内保持注册表的顺序（{@code order} 是同一层内的显式序）。
     *
     * @param session 会话运行态
     * @return 拼接后的贡献文本；无人贡献时返回 {@code null}
     */
    private String contributionsOf(Session session) {
        List<HandlerBinding<PromptContributionRequest, PromptContribution>> bindings =
                extensions.bindings(PromptContributionRequest.class, null);
        if (bindings.isEmpty()) {
            return null;
        }
        // 同一个请求对象复用给全部处理器：载荷只有 sessionId，处理器只读
        PromptContributionRequest request = new PromptContributionRequest(session.getSessionId());
        StringBuilder text = new StringBuilder();
        // 按 values() 的声明顺序遍历：那份顺序就是「由稳定到易变」，也就是想要的拼接顺序
        for (PromptPlacement placement : PromptPlacement.values()) {
            appendPlacement(text, bindings, request, placement);
        }
        return text.length() == 0 ? null : text.toString();
    }

    /**
     * 追加某一分层的全部非空块，保持注册表给的顺序。
     *
     * @param text      目标缓冲
     * @param bindings  全部贡献处理器（已按 {@code order} 升序）
     * @param request   贡献请求
     * @param placement 本道要取的分层
     */
    private void appendPlacement(StringBuilder text,
                                 List<HandlerBinding<PromptContributionRequest, PromptContribution>> bindings,
                                 PromptContributionRequest request, PromptPlacement placement) {
        for (HandlerBinding<PromptContributionRequest, PromptContribution> binding : bindings) {
            PromptContribution contribution = contributionOf(binding, request);
            if (contribution == null || contribution.isEmpty() || contribution.getPlacement() != placement) {
                continue;
            }
            appendBlock(text, contribution.getText());
        }
    }

    /**
     * 询问各插件「本轮有没有要随用户消息一起送达的即时状态」，按注册顺序拼接。
     * <p>
     * <b>与 {@link #contributionsOf} 的分工</b>：那个的产物进 system prompt，也就是缓存前缀的
     * 第 0 个 token；本方法的产物由 {@code ReActLooper} 拼进<b>本轮用户消息</b>并随消息落盘，
     * 因此是 append-only 的——它只影响本轮新产生的 token，对已经发送过的内容没有任何影响。
     * 待办进度这类「会说变就变」的状态因此不该进 system prompt。
     * <p>
     * <b>失败口径与贡献块一致</b>：单个处理器抛错只记 WARN 跳过，不阻断对话。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @param userInput 本轮用户输入原文，可为 {@code null}
     * @param nested    是否嵌套回合
     * @return 拼接后的回合上下文；无人应答时返回 {@code null}
     */
    public String turnContextOf(String sessionId, String userInput, boolean nested) {
        List<HandlerBinding<TurnContextRequest, TurnContext>> bindings =
                extensions.bindings(TurnContextRequest.class, null);
        if (bindings.isEmpty()) {
            return null;
        }
        // 同一个请求对象复用给全部处理器：载荷只有会话标识与输入原文，处理器只读
        TurnContextRequest request = new TurnContextRequest(sessionId, userInput, nested);
        StringBuilder text = new StringBuilder();
        for (HandlerBinding<TurnContextRequest, TurnContext> binding : bindings) {
            TurnContext context = turnContextOf(binding, request);
            if (context == null || context.isEmpty()) {
                continue;
            }
            appendBlock(text, context.getText());
        }
        return text.length() == 0 ? null : text.toString();
    }

    /**
     * 执行单个贡献处理器并取出贡献。
     *
     * @param binding 处理器绑定（含 owner，供告警归因）
     * @param request 贡献请求
     * @return 贡献；无贡献或处理失败时返回 {@code null}
     */
    private PromptContribution contributionOf(HandlerBinding<PromptContributionRequest, PromptContribution> binding,
                                              PromptContributionRequest request) {
        try {
            return extensions.invoke(binding.getHandler(), request);
        } catch (Exception e) {
            LOG.warn("提示词贡献处理器执行失败，已跳过: owner={} reason={}", binding.getOwner(), e.getMessage());
            return null;
        }
    }

    /**
     * 执行单个回合上下文处理器并取出结果。
     *
     * @param binding 处理器绑定（含 owner，供告警归因）
     * @param request 回合上下文请求
     * @return 结果；无内容或处理失败时返回 {@code null}
     */
    private TurnContext turnContextOf(HandlerBinding<TurnContextRequest, TurnContext> binding,
                                      TurnContextRequest request) {
        try {
            return extensions.invoke(binding.getHandler(), request);
        } catch (Exception e) {
            LOG.warn("回合上下文处理器执行失败，已跳过: owner={} reason={}", binding.getOwner(), e.getMessage());
            return null;
        }
    }

    /**
     * 计算可用 token 预算。
     * <p>
     * 预算只算一次并往下传：老化的水位判据、裁剪的边界、{@link ContextUsage} 的分母必须是同一个
     * 数——三处各算一遍就会出现「一个说还很宽裕、一个已经开始丢历史」的相反结论。
     *
     * @param resolvedModel 已解析的模型
     * @return 可用预算；模型没配上下文窗口时返回 {@link #NO_CONTEXT_WINDOW}
     *         （与「预算恰好为 0」区分开）
     */
    private int budgetOf(ResolvedModel resolvedModel) {
        int contextLength = resolvedModel.getModel().getContextLength();
        if (contextLength <= 0) {
            return NO_CONTEXT_WINDOW;
        }
        return contextLength - resolvedModel.getModel().getMaxOutputTokens()
                - reactSettings().getContextReserveTokens();
    }

    /**
     * 按预算裁剪会话历史，并把「预算」与「是否裁过」一起回给调用方。
     *
     * @param budget       可用预算，{@link #NO_CONTEXT_WINDOW} 表示模型没配窗口
     * @param systemPrompt 已组装的 system prompt，可为 {@code null}
     * @param summary      将要前置的摘要合成消息，可为 {@code null}
     * @param history       压缩边界之后的全部历史消息，不可为 {@code null}
     * @return 裁剪结果，保证非 {@code null}
     */
    private static CropResult crop(int budget, String systemPrompt, LlmMessage summary,
                                   List<LlmMessage> history) {
        if (budget == NO_CONTEXT_WINDOW) {
            // 没配上下文窗口就无从判断预算，原样下发（宁可让厂商报错，也不静默丢历史）
            return new CropResult(history, 0, false);
        }
        // 摘要也要算进预算：它在裁剪之后才拼上去，不先扣掉就会让历史挤掉本该留给它的位置
        int historyBudget = budget - TokenEstimator.estimate(systemPrompt) - TokenEstimator.estimateMessage(summary);
        ContextWindow.Result result = ContextWindow.crop(history, historyBudget);
        return new CropResult(result.getMessages(), budget, result.isTruncated());
    }

    /**
     * 一次裁剪的结果：发出的消息、可用预算、是否裁过。
     * <p>
     * 预算一并带出来，是因为「用量占预算的比例」才是压缩的触发判据——只有用过多少而没有分母，
     * 那个比例算不出来。{@code budget <= 0} 同时用来表示「模型没配窗口、无从判断」。
     */
    private static final class CropResult {

        /** 实际发出的历史消息。 */
        private final List<LlmMessage> messages;

        /** 所有消息可用的总 token 预算（已扣掉输出与预留）。 */
        private final int budget;

        /** 是否发生了机械裁剪。 */
        private final boolean truncated;

        /**
         * 构造裁剪结果。
         *
         * @param messages  实际发出的消息，不可为 {@code null}
         * @param budget    可用预算，{@code <= 0} 表示无从判断
         * @param truncated 是否发生了裁剪
         */
        private CropResult(List<LlmMessage> messages, int budget, boolean truncated) {
            this.messages = messages;
            this.budget = budget;
            this.truncated = truncated;
        }

        /**
         * 获取实际发出的消息。
         *
         * @return 消息列表
         */
        private List<LlmMessage> getMessages() {
            return messages;
        }

        /**
         * 获取可用预算。
         *
         * @return 预算 token 数
         */
        private int getBudget() {
            return budget;
        }

        /**
         * 判断是否发生了裁剪。
         *
         * @return 裁过返回 {@code true}
         */
        private boolean isTruncated() {
            return truncated;
        }
    }

    /**
     * 取当前 ReAct 段配置。
     * <p>
     * 每次现读而不是缓存：配置热更新后新请求立刻拿到新预留值。
     *
     * @return ReAct 段配置，保证非 {@code null}
     */
    private ReactSettings reactSettings() {
        return runtimeConfig.getReactSettings();
    }

    /**
     * 求实际的第一条要发送的消息下标，并跳过开头的孤儿工具结果。
     * <p>
     * <b>为什么需要跳过</b>：出站序列必须满足工具调用配对约束（见 {@link ToolPairing}）。压缩已经对齐过
     * 边界，但恢复出来的会话可能带着旧版本写的、或手工改过的边界，因此这里再兜一层——
     * 以孤儿 {@code tool} 消息开头的请求会被厂商直接拒（400）。
     * <p>
     * 单独成方法是因为父请求装配要同时知道「发出去的历史从哪里开始」，见 {@link #parentOf}。
     *
     * @param session    会话运行态
     * @param firstIndex 候选起点下标
     * @return 实际起点下标，落在 {@code [0, size]} 内
     */
    private static int startIndexOf(Session session, int firstIndex) {
        List<SessionMessage> messages = session.getMessages();
        int start = Math.max(0, firstIndex);
        while (start < messages.size() && ToolPairing.isToolResult(messages.get(start).getMessage())) {
            start++;
        }
        return start;
    }

    /**
     * 把会话消息投影成厂商无关的消息列表，并跳过已被摘要覆盖的那一段。
     * <p>
     * <b>为什么在这里截断而不是删消息</b>：{@code /compact} 是非破坏式的——消息一条不删，
     * 屏幕投影、持久化与 {@code /resume} 看到的仍是完整历史，只有「发给模型的那条链路」按边界截。
     * 摘要也因此走「每次现算一条合成消息」而不是落进会话：它一旦被 append 回会话，
     * 就会每轮重复累积，越聊越像一份不断膨胀的假历史。
     * <p>
     * <b>出站前还要过一遍工具调用配对约束</b>：切出来的序列两端都可能非法（开头是孤儿工具结果、
     * 结尾是悬空的工具调用），两类都会让厂商以 400 拒掉整次请求，详见 {@link ToolPairing}。
     *
     * @param session    会话运行态
     * @param start      第一条要发送的消息下标（已跳过孤儿工具结果）
     * @return 不可修改的消息列表
     */
    private static List<LlmMessage> toLlmMessages(Session session, int start) {
        List<SessionMessage> messages = session.getMessages();
        List<LlmMessage> llmMessages = new ArrayList<LlmMessage>(messages.size());
        for (int index = start; index < messages.size(); index++) {
            llmMessages.add(messages.get(index).getMessage());
        }
        dropTrailingDanglingToolCalls(session.getSessionId(), llmMessages);
        return llmMessages;
    }

    /**
     * 丢弃结尾那段「工具调用一条结果都没落盘」的 assistant 消息。
     * <p>
     * <b>为什么需要它</b>：会话结尾的 {@code assistant(toolCalls)} 若没有任何 {@code tool} 结果跟随，
     * 发出去就是非法请求（见 {@link ToolPairing}）。正常路径下不会走到这里——回合被取消时
     * {@code ReActLooper} 会给未执行的工具调用补上合成结果。剩下的是三类异常现场：进程崩溃在
     * 「落完 assistant、还没落结果」之间、{@code /resume} 一份旧版本写的会话、以及手工改过会话文件。
     * <p>
     * <b>为什么是丢弃而不是补发</b>：补发要写回会话，而本方法在「组装本次请求」的路径上，
     * 那条路径刻意不写会话（与 {@code ContextWindow} / {@code ToolResultAger} 同一口径）。
     * 丢弃只是少发一条「本就没有下文」的消息，代价远小于让整个会话发不出去。
     * <p>
     * <b>只处理结尾</b>：中间的配对缺失（一组工具只落了部分结果）不会在这里修——它同样来自上述
     * 异常现场，但修它要拆掉一个已经存在并且可能被引用到的工具调用，风险与收益不相称。
     *
     * @param sessionId 会话标识，仅供告警归因
     * @param messages  已按边界切好的消息列表，会被就地修改
     */
    private static void dropTrailingDanglingToolCalls(String sessionId, List<LlmMessage> messages) {
        int dropped = 0;
        while (!messages.isEmpty() && ToolPairing.requiresToolResults(messages.get(messages.size() - 1))) {
            messages.remove(messages.size() - 1);
            dropped++;
        }
        if (dropped > 0) {
            LOG.warn("会话结尾有 {} 条工具调用没有对应结果（取消或崩溃留下的），已从本次请求中丢弃: sessionId={}",
                    dropped, sessionId);
        }
    }
}
