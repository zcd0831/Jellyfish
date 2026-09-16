package zcd.jellyfish.core.prompt;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.extension.PromptContribution;
import zcd.jellyfish.api.extension.PromptContributionRequest;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.ReactSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.extension.HandlerBinding;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmRequest;
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
 *     <li><b>system prompt</b>：agent 提示词原文 + 各插件贡献块 + 历史摘要（{@code /compact} 压出来的
 *     那一块）；三者都为空则不下发；</li>
 *     <li><b>消息历史</b>：先跳过已被压缩摘要覆盖的那一段，再按模型上下文窗口做机械裁剪
 *     （只裁本次请求，不写回会话）；</li>
 *     <li><b>工具清单</b>：来自 {@link ToolCatalog}，与注册表同源；</li>
 *     <li><b>采样参数</b>：模型名取 {@code Model.getId()}、最大输出取 {@code Model.getMaxOutputTokens()}。</li>
 * </ol>
 * <b>插件上下文为什么拼进 system prompt 而不是消息列表</b>：它是「本轮的上下文注入」，不是对话历史。
 * 塞进 {@code messages} 会被后续 append 回会话，导致每轮重复累积、回放与 token 统计失真。
 * 待办、记忆召回这类插件状态因此只能走
 * {@link PromptContributionRequest}，由插件自己决定这一轮要不要说、说什么。
 *
 * @author zcd
 */
@Singleton
public class PromptAssembler {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(PromptAssembler.class);

    /** 贡献块之间的分隔符。 */
    private static final String BLOCK_SEPARATOR = "\n\n";

    /** agent 提示词。 */
    private final AgentManager agentManager;

    /** 工具目录。 */
    private final ToolCatalog toolCatalog;

    /** ReAct 运行期参数，提供上下文预留。 */
    private final RuntimeConfig runtimeConfig;

    /** 同步扩展点策略，提示词贡献的唯一来源。 */
    private final ExtensionRegistry extensions;

    /**
     * 构造提示词组装器。
     *
     * @param agentManager  agent 门面
     * @param toolCatalog   工具目录
     * @param runtimeConfig 运行时配置门面（读取 ReAct 段的预留 token）
     * @param extensions    同步扩展点策略（取本轮提示词贡献）
     */
    @Inject
    public PromptAssembler(AgentManager agentManager, ToolCatalog toolCatalog, RuntimeConfig runtimeConfig,
                           ExtensionRegistry extensions) {
        this.agentManager = Objects.requireNonNull(agentManager, "agentManager must not be null");
        this.toolCatalog = Objects.requireNonNull(toolCatalog, "toolCatalog must not be null");
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig must not be null");
        this.extensions = Objects.requireNonNull(extensions, "extensions must not be null");
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
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(resolvedModel, "resolvedModel must not be null");
        int boundary = effectiveBoundary(session);
        String systemPrompt = systemPromptOf(session, boundary);
        List<LlmMessage> history = toLlmMessages(session, boundary + 1);
        CropResult cropResult = crop(resolvedModel, systemPrompt, history);
        LlmRequest.Builder builder = LlmRequest.builder(resolvedModel.getModel().getId())
                .systemPrompt(systemPrompt)
                .messages(cropResult.getMessages())
                .tools(toolCatalog.tools());
        int maxOutputTokens = resolvedModel.getModel().getMaxOutputTokens();
        if (maxOutputTokens > 0) {
            builder.maxTokens(maxOutputTokens);
        }
        ContextUsage usage = new ContextUsage(TokenEstimator.estimate(systemPrompt)
                + TokenEstimator.estimateMessages(cropResult.getMessages()), cropResult.getBudget(),
                cropResult.isTruncated());
        return new PromptAssembly(builder.build(), usage);
    }

    /**
     * 组装 system prompt：agent 提示词原文 + 各插件贡献块 + 历史摘要。
     * <p>
     * <b>摘要排在最后</b>（在插件贡献之后）是有意的：它是对「远古对话」的压缩，越靠后离当前对话越近，
     * 模型越容易把它当成背景而不是当前指令；排在 agent 提示词之前则会反过来——一段可能是几天前的
     * 总结会压住本次会话的角色设定。
     *
     * @param session 会话运行态
     * @return system prompt；三者都为空时返回 {@code null}（不下发）
     */
    public String systemPromptOf(Session session) {
        return systemPromptOf(session, effectiveBoundary(session));
    }

    /**
     * 组装 system prompt，复用已解析的压缩边界。
     *
     * @param session  会话运行态
     * @param boundary 有效的压缩边界下标，{@code -1} 表示没有有效压缩
     * @return system prompt；三者都为空时返回 {@code null}（不下发）
     */
    private String systemPromptOf(Session session, int boundary) {
        StringBuilder text = new StringBuilder();
        appendBlock(text, agentManager.systemPromptOf(session.getAgentId()));
        appendBlock(text, contributionsOf(session));
        appendBlock(text, summaryBlockOf(session, boundary));
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
     * 把压缩摘要包装成 system prompt 里的一块。
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
     * @return 摘要块；没有有效压缩时返回 {@code null}
     */
    private static String summaryBlockOf(Session session, int boundary) {
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
        return text.append("。摘要如下：\n").append(compaction.getSummary()).toString();
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
     * 询问各插件本轮要贡献的上下文，按注册顺序拼接。
     * <p>
     * <b>单个处理器失败只记告警并跳过</b>：贡献是「锦上添花」的上下文，一个坏插件不该让整个对话
     * 发不出去——这与「工具失败转成 tool 结果」是同一种取舍，只有模型调用本身失败才上抛。
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
        for (HandlerBinding<PromptContributionRequest, PromptContribution> binding : bindings) {
            String fragment = fragmentOf(binding, request);
            if (StringUtils.isBlank(fragment)) {
                continue;
            }
            if (text.length() > 0) {
                text.append(BLOCK_SEPARATOR);
            }
            text.append(fragment.trim());
        }
        return text.length() == 0 ? null : text.toString();
    }

    /**
     * 执行单个贡献处理器并取出文本。
     *
     * @param binding 处理器绑定（含 owner，供告警归因）
     * @param request 贡献请求
     * @return 贡献文本；无贡献或处理失败时返回 {@code null}
     */
    private String fragmentOf(HandlerBinding<PromptContributionRequest, PromptContribution> binding,
                              PromptContributionRequest request) {
        try {
            PromptContribution contribution = extensions.invoke(binding.getHandler(), request);
            return contribution == null ? null : contribution.getText();
        } catch (Exception e) {
            LOG.warn("提示词贡献处理器执行失败，已跳过: owner={} reason={}", binding.getOwner(), e.getMessage());
            return null;
        }
    }

    /**
     * 按模型上下文窗口裁剪会话历史，并把「预算」与「是否裁过」一起回给调用方。
     *
     * @param resolvedModel 已解析的模型
     * @param systemPrompt  已组装的 system prompt，可为 {@code null}
     * @param history       压缩边界之后的全部历史消息，不可为 {@code null}
     * @return 裁剪结果，保证非 {@code null}
     */
    private CropResult crop(ResolvedModel resolvedModel, String systemPrompt, List<LlmMessage> history) {
        int contextLength = resolvedModel.getModel().getContextLength();
        if (contextLength <= 0) {
            // 没配上下文窗口就无从判断预算，原样下发（宁可让厂商报错，也不静默丢历史）
            return new CropResult(history, 0, false);
        }
        int budget = contextLength - resolvedModel.getModel().getMaxOutputTokens()
                - reactSettings().getContextReserveTokens();
        int historyBudget = budget - TokenEstimator.estimate(systemPrompt);
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
     * 把会话消息投影成厂商无关的消息列表，并跳过已被摘要覆盖的那一段。
     * <p>
     * <b>为什么在这里截断而不是删消息</b>：{@code /compact} 是非破坏式的——消息一条不删，
     * 屏幕投影、持久化与 {@code /resume} 看到的仍是完整历史，只有「发给模型的那条链路」按边界截。
     * 这也是为什么摘要在 system prompt 里而不是作为一条消息回灌：它一旦进了 {@code messages}，
     * 就会被后续每轮重复 append 回会话，越聊越像一份不断膨胀的假历史。
     *
     * @param session    会话运行态
     * @param firstIndex 第一条要发送的消息下标
     * @return 不可修改的消息列表
     */
    private static List<LlmMessage> toLlmMessages(Session session, int firstIndex) {
        List<SessionMessage> messages = session.getMessages();
        List<LlmMessage> llmMessages = new ArrayList<LlmMessage>(messages.size());
        for (int index = Math.max(0, firstIndex); index < messages.size(); index++) {
            llmMessages.add(messages.get(index).getMessage());
        }
        return llmMessages;
    }
}
