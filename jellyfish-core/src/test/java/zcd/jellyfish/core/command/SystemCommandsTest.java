package zcd.jellyfish.core.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.extension.CompactionStrategy;
import zcd.jellyfish.api.extension.CompactionStrategyRequest;
import zcd.jellyfish.core.compact.ConversationCompactor;
import zcd.jellyfish.infra.config.ConfigReloader;
import zcd.jellyfish.infra.config.Model;
import zcd.jellyfish.infra.config.Provider;
import zcd.jellyfish.infra.config.ReactSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.llm.LlmClient;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmRequest;
import zcd.jellyfish.infra.llm.LlmResponse;
import zcd.jellyfish.infra.llm.LlmStreamHandle;
import zcd.jellyfish.infra.llm.LlmStreamListener;
import zcd.jellyfish.infra.model.ResolvedModel;
import zcd.jellyfish.api.extension.CommandChoice;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.PermissionMode;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.command.CommandManager;
import zcd.jellyfish.infra.config.AgentDefinition;
import zcd.jellyfish.infra.config.Model;
import zcd.jellyfish.infra.config.Provider;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmUsage;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.model.ResolvedModel;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link SystemCommands} 的单元测试：验证系统命令的副作用写回、错误分支与注册回收。
 * <p>
 * 用真实 {@code ExtensionRegistry} + 真实 {@code CommandManager} + 真实 {@code SessionManager}，
 * 只 mock 模型与 agent 门面这类外部协作者。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class SystemCommandsTest {

    /** agent 门面。 */
    @Mock
    private AgentManager agentManager;

    /** 模型门面。 */
    @Mock
    private ModelManager modelManager;

    /** 通知发布入口，仅用于构造真实 SessionManager。 */
    @Mock
    private EventPublisher events;

    /** 运行时配置门面：提供压缩默认档位。 */
    @Mock
    private RuntimeConfig runtimeConfig;

    /** 配置重载器：{@code /reload} 的执行体。 */
    @Mock
    private ConfigReloader configReloader;

    /** 真实会话压缩器：{@code /compact} 的执行体。 */
    private ConversationCompactor compactor;

    /** 压缩策略的注册句柄：部分用例把它关掉，模拟「没装压缩插件」。 */
    private Subscription strategySubscription;

    /** 真实命令域服务。 */
    private CommandManager commandManager;

    /** 真实会话域服务。 */
    private SessionManager sessionManager;

    /** 被测系统命令注册器。 */
    private SystemCommands systemCommands;

    @BeforeEach
    void setUp() {
        ExtensionRegistry extensions = new ExtensionRegistry(new TypeRegistry());
        commandManager = new CommandManager(extensions, events);
        sessionManager = new SessionManager(agentManager, events, extensions);
        // 只有 /compact 需要它，用 lenient 免得其余用例因「多余打桩」被 Mockito 判失败
        lenient().when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        // 压缩策略由插件提供：命令层用例备一个兜底策略，模拟「装了压缩插件」
        ExtensionRegistry strategyRegistry = new ExtensionRegistry(new TypeRegistry());
        strategySubscription = strategyRegistry.contribute("test-plugin", CompactionStrategyRequest.class, null,
                request -> new CompactionStrategy("压成摘要，不超过 {maxSummaryChars} 字", null, null),
                RegisterOptions.DEFAULT);
        compactor = new ConversationCompactor(sessionManager, modelManager, runtimeConfig,
                strategyRegistry, events);
        systemCommands = new SystemCommands(extensions, commandManager, sessionManager, modelManager, agentManager,
                events, compactor, runtimeConfig, configReloader);
        systemCommands.register();
    }

    @Test
    void register_should_expose_expected_commands() {
        // When
        List<String> names = commandManager.commands().stream()
                .map(info -> info.getName()).collect(Collectors.toList());

        // Then
        assertTrue(names.containsAll(Arrays.asList("help", "new", "session", "resume", "model", "agent", "mode",
                "status", "usage", "delete", "compact", "reload")));
        assertEquals(12, names.size());
    }

    @Test
    void close_should_remove_all_commands() {
        // When
        systemCommands.close();

        // Then
        assertTrue(commandManager.commands().isEmpty());
    }

    @Test
    void help_should_render_command_list() {
        // When
        CommandResult result = commandManager.execute("/help");

        // Then
        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertTrue(result.getOutput().contains("可用命令"));
    }

    @Test
    void new_should_create_and_switch_current_session() {
        // When
        CommandResult result = commandManager.execute("/new");

        // Then
        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertNotNull(sessionManager.current());
        assertTrue(result.getOutput().contains(sessionManager.current().getSessionId()));
    }

    @Test
    void session_should_list_all_sessions_and_mark_current() {
        // Given
        commandManager.execute("/new");
        commandManager.execute("/new");
        Session current = sessionManager.current();

        // When
        CommandResult result = commandManager.execute("/session");

        // Then
        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertTrue(result.getOutput().contains(current.getSessionId()));
        assertTrue(result.getOutput().contains("* " + current.getSessionId()));
    }

    @Test
    void resume_should_switch_and_report_missing_session() {
        // Given
        Session first = sessionManager.createDefault();
        commandManager.execute("/new");

        // When
        CommandResult ok = commandManager.execute("/resume " + first.getSessionId());
        CommandResult missing = commandManager.execute("/resume ghost");

        // Then
        assertEquals(CommandResult.Kind.OK, ok.getKind());
        assertEquals(first.getSessionId(), sessionManager.current().getSessionId());
        assertEquals(CommandResult.Kind.ERROR, missing.getKind());
    }

    @Test
    void resume_without_argument_should_offer_session_choices() {
        // Given：两个会话，当前为第二个
        Session first = sessionManager.createDefault();
        commandManager.execute("/new");

        // When
        CommandResult result = commandManager.execute("/resume");

        // Then
        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertTrue(result.hasChoices());
        assertEquals(2, result.getChoices().size());
        assertEquals(first.getSessionId(), result.getChoices().get(0).getValue());
        assertTrue(result.getChoices().get(1).isCurrent());
    }

    @Test
    void model_without_argument_should_list_models() {
        // Given
        when(modelManager.getProviders()).thenReturn(
                Collections.singletonList(provider("openai", "gpt-4o")));

        // When
        CommandResult result = commandManager.execute("/model");

        // Then
        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertTrue(result.getOutput().contains("openai/gpt-4o"));
        assertTrue(result.hasChoices());
        assertEquals("openai/gpt-4o", result.getChoices().get(0).getValue());
    }

    @Test
    void model_with_argument_should_switch_session_model() {
        // Given
        commandManager.execute("/new");
        when(modelManager.resolve("openai", "gpt-4o")).thenReturn(resolvedModel("openai", "gpt-4o"));

        // When
        CommandResult result = commandManager.execute("/model openai/gpt-4o");

        // Then
        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertEquals("openai", sessionManager.current().getProvider());
        assertEquals("gpt-4o", sessionManager.current().getModel());
    }

    @Test
    void model_without_argument_should_report_error_when_session_missing() {
        // When
        CommandResult result = commandManager.execute("/model openai/gpt-4o");

        // Then
        assertEquals(CommandResult.Kind.ERROR, result.getKind());
    }

    @Test
    void agent_without_argument_should_list_agents() {
        // Given
        when(agentManager.all()).thenReturn(Collections.singletonList(definition("coder")));
        when(agentManager.getDefaultAgentId()).thenReturn("coder");

        // When
        CommandResult result = commandManager.execute("/agent");

        // Then
        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertTrue(result.getOutput().contains("coder"));
        assertTrue(result.getOutput().contains("[默认]"));
        assertTrue(result.hasChoices());
        assertEquals("coder", result.getChoices().get(0).getValue());
        assertTrue(result.getChoices().get(0).getDescription().contains("默认"));
    }

    @Test
    void agent_with_argument_should_bind_and_report_missing() {
        // Given
        commandManager.execute("/new");
        when(agentManager.require("coder")).thenReturn(definition("coder"));
        when(agentManager.require("ghost")).thenThrow(new JellyfishException("not found"));
        when(agentManager.systemPromptOf("coder")).thenReturn("prompt");

        // When
        CommandResult ok = commandManager.execute("/agent coder");
        CommandResult missing = commandManager.execute("/agent ghost");

        // Then
        assertEquals(CommandResult.Kind.OK, ok.getKind());
        assertEquals("coder", sessionManager.current().getAgentId());
        assertEquals(CommandResult.Kind.ERROR, missing.getKind());
    }

    @Test
    void agent_with_argument_should_warn_when_prompt_file_missing() {
        // Given：agent 声明了权限配置，但同目录下没有同名 md 提示词
        commandManager.execute("/new");
        when(agentManager.require("coder")).thenReturn(definition("coder"));
        when(agentManager.systemPromptOf("coder")).thenReturn(null);

        // When
        CommandResult result = commandManager.execute("/agent coder");

        // Then：切换仍成功（权限配置照常生效），但结果里必须说清楚没有提示词
        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertEquals("coder", sessionManager.current().getAgentId());
        assertTrue(result.getOutput().contains("没有系统提示词"));
    }

    @Test
    void mode_should_show_and_switch_permission_mode() {
        // Given
        commandManager.execute("/new");

        // When
        CommandResult show = commandManager.execute("/mode");
        CommandResult plan = commandManager.execute("/mode plan");
        CommandResult invalid = commandManager.execute("/mode bogus");

        // Then
        assertTrue(show.getOutput().contains("normal"));
        assertTrue(show.hasChoices());
        assertEquals("normal", show.getChoices().get(1).getValue());
        assertTrue(show.getChoices().get(1).isCurrent());
        assertEquals(PermissionMode.PLAN, sessionManager.current().getPermissionMode());
        assertEquals(CommandResult.Kind.ERROR, invalid.getKind());
    }

    @Test
    void status_and_usage_should_render_current_session_state() {
        // Given
        commandManager.execute("/new");
        Session session = sessionManager.current();
        sessionManager.appendMessage(session.getSessionId(), LlmMessage.user("hi"), new LlmUsage(1, 2, 3));

        // When
        CommandResult status = commandManager.execute("/status");
        CommandResult usage = commandManager.execute("/usage");

        // Then
        assertTrue(status.getOutput().contains(session.getSessionId()));
        assertTrue(usage.getOutput().contains("3"));
    }

    @Test
    void commands_should_report_error_when_no_current_session() {
        // When / Then：依赖会话的命令在无当前会话时应明确报错而不是 NPE
        assertEquals(CommandResult.Kind.ERROR, commandManager.execute("/status").getKind());
        assertEquals(CommandResult.Kind.ERROR, commandManager.execute("/usage").getKind());
        assertEquals(CommandResult.Kind.ERROR, commandManager.execute("/mode").getKind());
        assertNull(sessionManager.current());
    }

    @Test
    void options_should_expose_agent_choices() {
        // Given
        when(agentManager.all()).thenReturn(Collections.singletonList(definition("coder")));
        when(agentManager.getDefaultAgentId()).thenReturn("coder");

        // When
        List<CommandChoice> options = commandManager.options("agent", null);

        // Then
        assertEquals(1, options.size());
        assertEquals("coder", options.get(0).getValue());
        assertTrue(options.get(0).getDescription().contains("默认"));
    }

    @Test
    void options_should_expose_model_choices() {
        // Given
        when(modelManager.getProviders()).thenReturn(
                Collections.singletonList(provider("openai", "gpt-4o")));

        // When
        List<CommandChoice> options = commandManager.options("model", null);

        // Then
        assertEquals(1, options.size());
        assertEquals("openai/gpt-4o", options.get(0).getValue());
    }

    @Test
    void options_should_expose_mode_choices_with_current_mode_marked() {
        // Given
        commandManager.execute("/new");

        // When
        List<CommandChoice> options = commandManager.options("mode", null);

        // Then：plan(0) / normal(1)，新会话默认 normal
        assertEquals(2, options.size());
        assertEquals("plan", options.get(0).getValue());
        assertTrue(options.get(1).isCurrent());
    }

    @Test
    void delete_should_remove_session_and_report_missing() {
        // Given
        commandManager.execute("/new");
        String sessionId = sessionManager.current().getSessionId();

        // When
        CommandResult result = commandManager.execute("/delete " + sessionId);

        // Then
        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertTrue(result.getOutput().contains(sessionId));
        assertNull(sessionManager.current());
        assertTrue(sessionManager.all().isEmpty());
    }

    @Test
    void delete_without_argument_should_offer_session_choices() {
        // Given
        commandManager.execute("/new");

        // When
        CommandResult result = commandManager.execute("/delete");

        // Then：无参时回退为候选清单（删除是破坏性操作，弹选择页比手敲 UUID 更稳）
        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertTrue(result.hasChoices());
        assertEquals(1, result.getChoices().size());
    }

    @Test
    void compact_should_reportUnavailable_when_noPluginProvidesStrategy() {
        // Given：把压缩策略的注册关掉 —— 模拟「没装压缩插件」
        commandManager.execute("/new");
        strategySubscription.close();

        // When
        CommandResult result = commandManager.execute("/compact");

        // Then：压不了要说清是「功能缺席」，而不是「没什么可压」
        assertEquals(CommandResult.Kind.ERROR, result.getKind());
        assertTrue(result.getOutput().contains("没有插件提供压缩策略"), result.getOutput());
    }

    @Test
    void compactPreview_should_reportUnavailable_when_noPluginProvidesStrategy() {
        // Given
        givenHistory(25);
        strategySubscription.close();

        // When
        CommandResult result = commandManager.execute("/compact preview");

        // Then：预览也要说清是功能缺席，并且不去解析模型（预览不该因为没模型而报别的错）
        assertEquals(CommandResult.Kind.ERROR, result.getKind());
        assertTrue(result.getOutput().contains("没有插件提供压缩策略"), result.getOutput());
    }

    @Test
    void status_should_markCompactionUnavailable_when_noPluginProvidesStrategy() {
        // Given
        commandManager.execute("/new");
        strategySubscription.close();

        // When
        CommandResult result = commandManager.execute("/status");

        // Then：用户在状态里就该看到压缩不可用
        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertTrue(result.getOutput().contains("压缩：不可用"), result.getOutput());
    }

    @Test
    void compact_without_argument_should_reportError_when_noHistory() {
        // Given：新会话没有任何历史
        commandManager.execute("/new");

        // When
        CommandResult result = commandManager.execute("/compact");

        // Then：无参即「按默认档位压一次」，没有可压的历史就是错误——用户要的动作没有发生
        assertEquals(CommandResult.Kind.ERROR, result.getKind());
        assertTrue(result.getOutput().contains("没有足够的历史可压缩"), result.getOutput());
        assertFalse(result.hasChoices());
    }

    @Test
    void compact_should_reject_unknown_value() {
        // Given
        commandManager.execute("/new");

        // When
        CommandResult result = commandManager.execute("/compact recent-3");

        // Then
        assertEquals(CommandResult.Kind.ERROR, result.getKind());
        assertTrue(result.getOutput().contains("用法：/compact [preview]"), result.getOutput());
    }

    @Test
    void compact_should_report_error_when_historyIsTooShort() {
        // Given：只有一条消息，没有可压的历史
        commandManager.execute("/new");
        sessionManager.appendMessage(sessionManager.current().getSessionId(), LlmMessage.user("你好"), null);

        // When
        CommandResult result = commandManager.execute("/compact");

        // Then
        assertEquals(CommandResult.Kind.ERROR, result.getKind());
        assertTrue(result.getOutput().contains("没有足够的历史可压缩"), result.getOutput());
    }

    @Test
    void compact_should_report_error_when_no_current_session() {
        // When：没有任何会话
        CommandResult result = commandManager.execute("/compact");

        // Then
        assertEquals(CommandResult.Kind.ERROR, result.getKind());
        assertTrue(result.getOutput().contains("/new"), result.getOutput());
    }

    @Test
    void compactPreview_should_report_plan_without_calling_model() {
        // Given：比缺省保留条数（20）多 5 条历史；只解析模型，不提供客户端
        givenHistory(25);
        givenCompactionModel();

        // When
        CommandResult result = commandManager.execute("/compact preview");

        // Then：预览只说会压多少条；若它真的去调模型，未打桩的客户端会当场炸掉
        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertTrue(result.getOutput().contains("将压缩 5 条消息"), result.getOutput());
        assertTrue(result.getOutput().contains("保留最近 20 条原文"), result.getOutput());
    }

    @Test
    void compactPreview_should_report_error_when_no_historyToCompress() {
        // Given：历史比保留条数还少
        givenHistory(3);

        // When
        CommandResult result = commandManager.execute("/compact preview");

        // Then：这是「没什么可压」的信息，不是失败
        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertTrue(result.getOutput().contains("没有可压缩的历史"), result.getOutput());
    }

    @Test
    void compact_should_startAndReport_when_historyEnough() throws Exception {
        // Given：模型门面能解析出模型（真压缩的细节由 ConversationCompactor 自有用例覆盖）
        givenHistory(25);
        ResolvedModel resolved = givenCompactionModel();
        when(modelManager.getClient(resolved)).thenReturn(chatClient());

        // When
        CommandResult result = commandManager.execute("/compact");

        // Then：命令只起头，文本明确「完成后会提示」而不是假装已经压完
        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertTrue(result.getOutput().contains("已开始压缩"), result.getOutput());
        // 等它真的收敛：既证明接线通了，也避免打桩在用例结束前还没被用到
        assertEquals(ConversationCompactor.Status.DONE, awaitCompaction());
    }

    /**
     * 建一个当前会话并追加指定条数的历史消息。
     *
     * @param count 消息条数
     */
    private void givenHistory(int count) {
        commandManager.execute("/new");
        String sessionId = sessionManager.current().getSessionId();
        for (int index = 1; index <= count; index++) {
            sessionManager.appendMessage(sessionId, LlmMessage.user("第 " + index + " 条"), null);
        }
    }

    /**
     * 桩上压缩用的模型解析链路：未配上下文窗口，因此整段历史一次压完。
     *
     * @return 解析出的模型
     */
    private ResolvedModel givenCompactionModel() {
        Model model = new Model("gpt-4o", "gpt-4o", 0, 0);
        Provider provider = new Provider("openai", "openai", null, null, null);
        ResolvedModel resolved = new ResolvedModel(provider, model);
        when(modelManager.resolveDefault()).thenReturn(resolved);
        return resolved;
    }

    /**
     * 等压缩离开进行中状态。
     *
     * @return 收敛后的状态
     * @throws InterruptedException 等待被中断时抛出
     */
    private ConversationCompactor.Status awaitCompaction() throws InterruptedException {
        String sessionId = sessionManager.current().getSessionId();
        for (int attempt = 0; attempt < 100; attempt++) {
            ConversationCompactor.Status status = compactor.status(sessionId).getStatus();
            if (status != ConversationCompactor.Status.RUNNING) {
                return status;
            }
            Thread.sleep(20L);
        }
        return compactor.status(sessionId).getStatus();
    }

    /**
     * 构造一个立刻返回摘要的假客户端：真压缩的细节由 ConversationCompactor 的用例覆盖。
     *
     * @return LLM 客户端
     */
    private static LlmClient chatClient() {
        return new LlmClient() {
            @Override
            public Provider getProvider() {
                return new Provider("openai", "openai", null, null, null);
            }

            @Override
            public LlmResponse chat(LlmRequest request) {
                return LlmResponse.text("摘要");
            }

            @Override
            public LlmStreamHandle chatStream(LlmRequest request, LlmStreamListener listener) {
                throw new UnsupportedOperationException("压缩不走流式");
            }
        };
    }

    @Test
    void delete_should_report_error_when_session_missing() {
        // When
        CommandResult result = commandManager.execute("/delete missing");

        // Then
        assertEquals(CommandResult.Kind.ERROR, result.getKind());
    }

    @Test
    void options_should_expose_session_choices() {
        // Given
        commandManager.execute("/new");

        // When
        List<CommandChoice> options = commandManager.options("resume", null);

        // Then
        assertEquals(1, options.size());
        assertTrue(options.get(0).isCurrent());
    }

    @Test
    void options_should_be_empty_for_command_without_options() {
        // When / Then
        assertTrue(commandManager.options("help", null).isEmpty());
        assertTrue(commandManager.options("ghost", null).isEmpty());
    }

    /**
     * 构造 provider。
     *
     * @param name      provider 名
     * @param modelName 模型名
     * @return provider
     */
    private static Provider provider(String name, String modelName) {
        Model model = new Model(modelName, modelName, 0, 0);
        return new Provider(name, name, null, null, Collections.singletonList(model));
    }

    /**
     * 构造解析结果。
     *
     * @param providerName provider 名
     * @param modelName    模型名
     * @return 解析结果
     */
    private static ResolvedModel resolvedModel(String providerName, String modelName) {
        return new ResolvedModel(provider(providerName, modelName), new Model(modelName, modelName, 0, 0));
    }

    /**
     * 构造 agent 定义。
     *
     * @param agentId agent 标识
     * @return 定义
     */
    private static AgentDefinition definition(String agentId) {
        return new AgentDefinition(agentId, "描述", null);
    }
}
