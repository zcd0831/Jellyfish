package zcd.jellyfish.core.prompt;

import org.apache.commons.lang3.StringUtils;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.ReactSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmRequest;
import zcd.jellyfish.infra.llm.LlmTool;
import zcd.jellyfish.infra.model.ResolvedModel;
import zcd.jellyfish.infra.session.PendingTodo;
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
 *     <li><b>system prompt</b>：agent 提示词原文 + 会话待办块；两者都为空则不下发；</li>
 *     <li><b>消息历史</b>：按模型上下文窗口做机械裁剪（只裁本次请求，不写回会话）；</li>
 *     <li><b>工具清单</b>：来自 {@link ToolCatalog}，与注册表同源；</li>
 *     <li><b>采样参数</b>：模型名取 {@code Model.getId()}、最大输出取 {@code Model.getMaxOutputTokens()}。</li>
 * </ol>
 * <b>待办为什么拼进 system prompt 而不是消息列表</b>：待办是「本轮的上下文注入」，不是对话历史。
 * 塞进 {@code messages} 会被后续 append 回会话，导致每轮重复累积、回放与 token 统计失真。
 *
 * @author zcd
 */
@Singleton
public class PromptAssembler {

    /** 待办块标题。 */
    private static final String TODO_HEADER = "\n\n[待办]\n";

    /** 未完成待办前缀。 */
    private static final String TODO_PENDING_PREFIX = "- [ ] ";

    /** 已完成待办前缀。 */
    private static final String TODO_DONE_PREFIX = "- [x] ";

    /** agent 门面，提供提示词原文。 */
    private final AgentManager agentManager;

    /** 工具目录。 */
    private final ToolCatalog toolCatalog;

    /** ReAct 运行期参数，提供上下文预留。 */
    private final RuntimeConfig runtimeConfig;

    /**
     * 构造提示词组装器。
     *
     * @param agentManager  agent 门面
     * @param toolCatalog   工具目录
     * @param runtimeConfig 运行时配置门面（读取 ReAct 段的预留 token）
     */
    @Inject
    public PromptAssembler(AgentManager agentManager, ToolCatalog toolCatalog, RuntimeConfig runtimeConfig) {
        this.agentManager = Objects.requireNonNull(agentManager, "agentManager must not be null");
        this.toolCatalog = Objects.requireNonNull(toolCatalog, "toolCatalog must not be null");
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig must not be null");
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
     * 组装 system prompt：agent 提示词原文 + 待办块。
     *
     * @param session 会话运行态
     * @return system prompt；agent 与待办都为空时返回 {@code null}（不下发）
     */
    public String systemPromptOf(Session session) {
        String agentPrompt = agentManager.systemPromptOf(session.getAgentId());
        String todoBlock = renderTodos(session.getTodos());
        if (StringUtils.isBlank(agentPrompt)) {
            return StringUtils.isBlank(todoBlock) ? null : todoBlock.trim();
        }
        if (StringUtils.isBlank(todoBlock)) {
            return agentPrompt;
        }
        return agentPrompt + todoBlock;
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

    /**
     * 渲染待办块。
     *
     * @param todos 待办列表
     * @return 待办块文本；无待办时返回 {@code null}
     */
    private static String renderTodos(List<PendingTodo> todos) {
        if (todos == null || todos.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder(TODO_HEADER);
        for (PendingTodo todo : todos) {
            text.append(todo.isDone() ? TODO_DONE_PREFIX : TODO_PENDING_PREFIX).append(todo.getContent()).append('\n');
        }
        return text.toString();
    }
}
