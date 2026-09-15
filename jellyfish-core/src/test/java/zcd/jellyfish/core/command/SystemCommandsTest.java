package zcd.jellyfish.core.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link SystemCommands} 的单元测试：验证十条系统命令的副作用写回、错误分支与注册回收。
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

    /** 真实命令域服务。 */
    private CommandManager commandManager;

    /** 真实会话域服务。 */
    private SessionManager sessionManager;

    /** 被测系统命令注册器。 */
    private SystemCommands systemCommands;

    @BeforeEach
    void setUp() {
        ExtensionRegistry extensions = new ExtensionRegistry(new TypeRegistry());
        commandManager = new CommandManager(extensions);
        sessionManager = new SessionManager(agentManager, events, extensions);
        systemCommands = new SystemCommands(extensions, commandManager, sessionManager, modelManager, agentManager);
        systemCommands.register();
    }

    @Test
    void register_should_expose_expected_commands() {
        // When
        List<String> names = commandManager.commands().stream()
                .map(info -> info.getName()).collect(Collectors.toList());

        // Then
        assertTrue(names.containsAll(Arrays.asList("help", "new", "session", "resume", "model", "agent", "mode",
                "status", "usage", "todo")));
        assertEquals(10, names.size());
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

        // When
        CommandResult ok = commandManager.execute("/agent coder");
        CommandResult missing = commandManager.execute("/agent ghost");

        // Then
        assertEquals(CommandResult.Kind.OK, ok.getKind());
        assertEquals("coder", sessionManager.current().getAgentId());
        assertEquals(CommandResult.Kind.ERROR, missing.getKind());
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
    void todo_should_add_complete_list_and_clear() {
        // Given
        commandManager.execute("/new");

        // When
        CommandResult added = commandManager.execute("/todo add 写文档");
        CommandResult listed = commandManager.execute("/todo");
        CommandResult done = commandManager.execute("/todo done 1");
        CommandResult afterDone = commandManager.execute("/todo");
        CommandResult cleared = commandManager.execute("/todo clear");

        // Then
        assertEquals(CommandResult.Kind.OK, added.getKind());
        assertTrue(listed.getOutput().contains("[ ] 1. 写文档"));
        assertTrue(done.getOutput().contains("1"));
        assertTrue(afterDone.getOutput().contains("[x] 1. 写文档"));
        assertTrue(cleared.getOutput().contains("1"));
        assertEquals(0, sessionManager.todosOf(sessionManager.current().getSessionId()).size());
    }

    @Test
    void todo_should_report_usage_errors() {
        // Given
        commandManager.execute("/new");

        // When
        CommandResult badAdd = commandManager.execute("/todo add");
        CommandResult badDone = commandManager.execute("/todo done 999");
        CommandResult badSub = commandManager.execute("/todo whatever");

        // Then
        assertEquals(CommandResult.Kind.ERROR, badAdd.getKind());
        assertEquals(CommandResult.Kind.ERROR, badDone.getKind());
        assertEquals(CommandResult.Kind.ERROR, badSub.getKind());
    }

    @Test
    void commands_should_report_error_when_no_current_session() {
        // When / Then：依赖会话的命令在无当前会话时应明确报错而不是 NPE
        assertEquals(CommandResult.Kind.ERROR, commandManager.execute("/status").getKind());
        assertEquals(CommandResult.Kind.ERROR, commandManager.execute("/usage").getKind());
        assertEquals(CommandResult.Kind.ERROR, commandManager.execute("/todo").getKind());
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
        return new AgentDefinition(agentId, "描述", null, null);
    }
}
