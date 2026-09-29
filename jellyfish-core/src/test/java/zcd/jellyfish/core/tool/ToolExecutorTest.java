package zcd.jellyfish.core.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.notification.ToolCallCompletedEvent;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.PermissionDecision;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolMetadata;
import zcd.jellyfish.core.ReActListener;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.ReactSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.permission.PermissionManager;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionDefaults;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.infra.tooloutput.ToolOutputLimiter;
import zcd.jellyfish.infra.tooloutput.ToolOutputStore;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ToolExecutor} 的单元测试：验证权限短路、异常转结果文本、截断与事件埋点。
 * <p>
 * 用真实 {@code ExtensionRegistry} / {@code SessionManager} / {@code ToolOutputLimiter}，
 * 只 mock 外部协作者（权限、事件、配置）。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class ToolExecutorTest {

    /** 权限管理器。 */
    @Mock
    private PermissionManager permissionManager;

    /** 通知发布入口。 */
    @Mock
    private EventPublisher events;

    /** 运行时配置门面。 */
    @Mock
    private RuntimeConfig runtimeConfig;

    /** agent 门面，只为装配真实会话服务。 */
    @Mock
    private AgentManager agentManager;

    /** 真实同步扩展点策略。 */
    private ExtensionRegistry extensions;

    /** 真实会话服务。 */
    private SessionManager sessionManager;

    /** 被测执行器。 */
    private ToolExecutor executor;

    /** 会话。 */
    private Session session;

    @BeforeEach
    void setUp() {
        extensions = new ExtensionRegistry(new TypeRegistry());
        sessionManager = new SessionManager(agentManager, events, extensions, new SessionDefaults());
        session = sessionManager.createDefault();
        ToolOutputLimiter outputLimiter = new ToolOutputLimiter(runtimeConfig, new ToolOutputStore(runtimeConfig));
        executor = new ToolExecutor(permissionManager, extensions, events, outputLimiter);
        lenient().when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
    }

    @Test
    void execute_should_return_denial_text_and_skip_handler_when_denied() {
        // Given
        when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.deny("危险命令"));
        boolean[] invoked = {false};
        register("shell", request -> {
            invoked[0] = true;
            return new ToolCallResult("shell", "ok");
        });

        // When
        ToolCallResult result = executor.execute(session, null, "c1", "shell", args("command", "rm -rf /"), null);

        // Then
        assertTrue(String.valueOf(result.getOutput()).contains("权限拒绝"));
        assertTrue(String.valueOf(result.getOutput()).contains("危险命令"));
        assertFalse(invoked[0]);
    }

    @Test
    void execute_should_route_to_handler_and_keep_metadata() {
        // Given
        when(permissionManager.decide(any(PermissionCheckRequest.class))).thenReturn(PermissionDecision.allow(null));
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("exitCode", Integer.valueOf(0));
        register("shell", request -> new ToolCallResult("shell", "hello", metadata));

        // When
        ToolCallResult result = executor.execute(session, null, "c1", "shell", args("command", "echo hi"), null);

        // Then
        assertEquals("hello", result.getOutput());
        assertEquals(Integer.valueOf(0), result.getMetadata().get("exitCode"));
        verify(events).publish(any(ToolCallCompletedEvent.class));
    }

    @Test
    void execute_should_turn_handler_failure_into_result_text() {
        // Given
        when(permissionManager.decide(any(PermissionCheckRequest.class))).thenReturn(PermissionDecision.allow(null));
        register("shell", request -> {
            throw new IllegalStateException("炸了");
        });

        // When
        ToolCallResult result = executor.execute(session, null, "c1", "shell", args("command", "x"), null);

        // Then
        assertTrue(String.valueOf(result.getOutput()).contains("工具执行失败"));
        assertTrue(String.valueOf(result.getOutput()).contains("炸了"));
    }

    @Test
    void execute_should_mark_terminal_failed_when_handler_throws() {
        // Given：工具按约定抛 JellyfishException，消息是给人看的一句原因
        when(permissionManager.decide(any(PermissionCheckRequest.class))).thenReturn(PermissionDecision.allow(null));
        register("read_file", request -> {
            throw new JellyfishException("文件不存在: /x/y");
        });

        // When
        ToolCallResult result = executor.execute(session, null, "c1", "read_file", args("path", "/x/y"), null);

        // Then：界面警示判据与原因都在元数据里
        assertEquals("FAILED", result.getMetadata().get(ToolMetadata.KEY_TERMINAL));
        assertTrue(ToolMetadata.failed(result.getMetadata()));
        assertEquals("文件不存在: /x/y", result.getMetadata().get(ToolMetadata.KEY_SUMMARY));
    }

    @Test
    void execute_should_not_include_summary_for_other_runtime_exception() {
        // Given：非约定异常的消息是实现细节，不进界面
        when(permissionManager.decide(any(PermissionCheckRequest.class))).thenReturn(PermissionDecision.allow(null));
        register("shell", request -> {
            throw new IllegalStateException("boom");
        });

        // When
        ToolCallResult result = executor.execute(session, null, "c1", "shell", args(), null);

        // Then
        assertEquals("FAILED", result.getMetadata().get(ToolMetadata.KEY_TERMINAL));
        assertFalse(result.getMetadata().containsKey(ToolMetadata.KEY_SUMMARY));
    }

    @Test
    void execute_should_not_set_terminal_when_handler_succeeds() {
        // Given
        when(permissionManager.decide(any(PermissionCheckRequest.class))).thenReturn(PermissionDecision.allow(null));
        register("shell", request -> new ToolCallResult("shell", "ok"));

        // When
        ToolCallResult result = executor.execute(session, null, "c1", "shell", args(), null);

        // Then：成功路径不带终止原因（缺省 = 正常跑完）
        assertFalse(result.getMetadata().containsKey(ToolMetadata.KEY_TERMINAL));
        assertFalse(ToolMetadata.failed(result.getMetadata()));
    }

    @Test
    void execute_should_pass_arguments_to_listener() {
        // Given
        when(permissionManager.decide(any(PermissionCheckRequest.class))).thenReturn(PermissionDecision.allow(null));
        register("shell", request -> new ToolCallResult("shell", "ok"));
        ReActListener listener = mock(ReActListener.class);
        Map<String, Object> arguments = args("command", "echo hi");

        // When
        executor.execute(session, null, "c1", "shell", arguments, listener);

        // Then：运行中目标靠这个重载传到外壳
        verify(listener).onToolCallStarted("c1", "shell", arguments);
    }

    @Test
    void execute_should_report_unknown_tool() {
        // Given
        when(permissionManager.decide(any(PermissionCheckRequest.class))).thenReturn(PermissionDecision.allow(null));

        // When
        ToolCallResult result = executor.execute(session, null, "c1", "nope", args(), null);

        // Then
        assertEquals("未知工具：nope", result.getOutput());
    }

    @Test
    void execute_with_json_should_parse_arguments_before_routing() {
        // Given
        when(permissionManager.decide(any(PermissionCheckRequest.class))).thenReturn(PermissionDecision.allow(null));
        register("shell", request -> new ToolCallResult("shell", request.getArguments().get("command")));

        // When
        ToolCallResult result = executor.execute(session, null, "c1", "shell", "{\"command\":\"ls\"}", null);

        // Then
        assertEquals("ls", result.getOutput());
    }

    @Test
    void execute_with_invalid_json_should_not_throw() {
        // Given：JSON 非法时甚至不会走到权限判定
        ToolCallResult result = executor.execute(session, null, "c1", "shell", "{not json", null);

        // Then
        assertTrue(String.valueOf(result.getOutput()).contains("工具参数解析失败"));
        verify(permissionManager, never()).decide(any(PermissionCheckRequest.class));
    }

    @Test
    void execute_should_default_null_output_to_empty_text() {
        // Given
        when(permissionManager.decide(any(PermissionCheckRequest.class))).thenReturn(PermissionDecision.allow(null));
        register("shell", request -> new ToolCallResult("shell", null));

        // When
        ToolCallResult result = executor.execute(session, null, "c1", "shell", args(), null);

        // Then
        assertEquals("", result.getOutput());
    }

    /**
     * 注册一个测试工具。
     *
     * @param name    工具名
     * @param handler 处理器
     */
    private void register(String name, ExtensionHandler<ToolCallRequest, ToolCallResult> handler) {
        extensions.handle("test", ToolCallRequest.class, name, null, handler, RegisterOptions.DEFAULT);
    }

    /**
     * 构造参数映射。
     *
     * @param namesAndValues 参数名与值交替
     * @return 参数映射
     */
    private static Map<String, Object> args(Object... namesAndValues) {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            arguments.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return arguments.isEmpty() ? Collections.<String, Object>emptyMap() : arguments;
    }
}
