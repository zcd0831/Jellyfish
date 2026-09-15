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
import zcd.jellyfish.infra.llm.LlmTool;
import zcd.jellyfish.infra.model.ResolvedModel;
import zcd.jellyfish.infra.session.Session;
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
 *     <li><b>消息历史</b>：按模型上下文窗口做机械裁剪（只裁本次请求，不写回会话）；</li>
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
     *
     * @param session       会话运行态，不可为 {@code null}
     * @param resolvedModel 已解析的模型，不可为 {@code null}
     * @return 统一请求模型
     */
    public LlmRequest buildRequest(Session session, ResolvedModel resolvedModel) {
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(resolvedModel, "resolvedModel must not be null");
        String systemPrompt = systemPromptOf(session);
        LlmRequest.Builder builder = LlmRequest.builder(resolvedModel.getModel().getId())
                .systemPrompt(systemPrompt)
                .messages(crop(session, resolvedModel, systemPrompt))
                .tools(toolCatalog.tools());
        int maxOutputTokens = resolvedModel.getModel().getMaxOutputTokens();
        if (maxOutputTokens > 0) {
            builder.maxTokens(maxOutputTokens);
        }
        return builder.build();
    }

    /**
     * 组装 system prompt：agent 提示词原文 + 各插件贡献块。
     *
     * @param session 会话运行态
     * @return system prompt；agent 提示词与所有贡献块都为空时返回 {@code null}（不下发）
     */
    public String systemPromptOf(Session session) {
        String agentPrompt = agentManager.systemPromptOf(session.getAgentId());
        String contribution = contributionsOf(session);
        if (StringUtils.isBlank(agentPrompt)) {
            return contribution;
        }
        if (StringUtils.isBlank(contribution)) {
            return agentPrompt;
        }
        return agentPrompt + BLOCK_SEPARATOR + contribution;
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
     * 按模型上下文窗口裁剪会话历史。
     *
     * @param session       会话运行态
     * @param resolvedModel 已解析的模型
     * @param systemPrompt  已组装的 system prompt，可为 {@code null}
     * @return 裁剪后的消息列表，保证非 {@code null}
     */
    private List<LlmMessage> crop(Session session, ResolvedModel resolvedModel, String systemPrompt) {
        List<LlmMessage> history = toLlmMessages(session);
        int contextLength = resolvedModel.getModel().getContextLength();
        if (contextLength <= 0) {
            // 没配上下文窗口就无从判断预算，原样下发（宁可让厂商报错，也不静默丢历史）
            return history;
        }
        int budget = contextLength - resolvedModel.getModel().getMaxOutputTokens()
                - reactSettings().getContextReserveTokens();
        int historyBudget = budget - TokenEstimator.estimate(systemPrompt);
        return ContextWindow.crop(history, historyBudget).getMessages();
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
     * 把会话消息投影成厂商无关的消息列表。
     *
     * @param session 会话运行态
     * @return 不可修改的消息列表
     */
    private static List<LlmMessage> toLlmMessages(Session session) {
        List<SessionMessage> messages = session.getMessages();
        List<LlmMessage> llmMessages = new ArrayList<LlmMessage>(messages.size());
        for (SessionMessage message : messages) {
            llmMessages.add(message.getMessage());
        }
        return llmMessages;
    }
}
