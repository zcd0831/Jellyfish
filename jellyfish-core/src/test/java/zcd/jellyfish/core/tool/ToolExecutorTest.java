package zcd.jellyfish.core.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.notification.ToolCallCompletedEvent;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.PermissionDecision;
import zcd.jellyfish.api.extension.ToolArgumentDecision;
import zcd.jellyfish.api.extension.ToolArgumentPreRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolMetadata;
import zcd.jellyfish.api.extension.ToolResultAdjustment;
import zcd.jellyfish.api.extension.ToolResultPostRequest;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
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

    /** 工具输出落盘存储：截断用例要断言「落下去的是哪一份内容」。 */
    @Mock
    private ToolOutputStore store;

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
        ToolOutputLimiter outputLimiter = new ToolOutputLimiter(runtimeConfig, store);
        executor = new ToolExecutor(permissionManager, extensions, events, outputLimiter);
        lenient().when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        lenient().when(store.store(anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn("/tmp/jellyfish-spill.txt");
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

    @Test
    void execute_should_pass_original_arguments_when_no_transform_registered() {
        // Given：0 handler 是兼容性承诺——行为必须与引入扩展点之前逐字段一致
        givenPermitted();
        Map<String, Object> metadata = args("exitCode", Integer.valueOf(0));
        register("shell", request -> new ToolCallResult("shell", "ok", metadata));
        ReActListener listener = mock(ReActListener.class);
        Map<String, Object> arguments = args("command", "echo hi");
        ArgumentCaptor<PermissionCheckRequest> captor = ArgumentCaptor.forClass(PermissionCheckRequest.class);

        // When
        ToolCallResult result = executor.execute(session, null, "c1", "shell", arguments, listener);

        // Then：参数一个字段没动，结果与元数据也一个字段没动
        verify(permissionManager).decide(captor.capture());
        assertEquals(arguments, captor.getValue().getArguments());
        verify(listener).onToolCallStarted("c1", "shell", arguments);
        assertEquals("ok", result.getOutput());
        assertEquals(Integer.valueOf(0), result.getMetadata().get("exitCode"));
    }

    @Test
    void execute_should_pass_null_arguments_through_when_no_transform_registered() {
        // Given：没有任何处理器时，参数应原样传下去（包括 null），不能顺手换成空映射
        givenPermitted();
        register("shell", request -> new ToolCallResult("shell", "ok"));
        ReActListener listener = mock(ReActListener.class);

        // When
        executor.execute(session, null, "c1", "shell", (Map<String, Object>) null, listener);

        // Then
        verify(listener).onToolCallStarted("c1", "shell", null);
    }

    @Test
    void execute_should_transform_arguments_before_permission_check() {
        // Given：参数改写排在权限判定之前，否则审批浮层显示参数 A、真正执行参数 B（TOCTOU）
        givenPermitted();
        onArguments(1, request -> ToolArgumentDecision.replace(args("command", "ls -l")));
        ArgumentCaptor<PermissionCheckRequest> captor = ArgumentCaptor.forClass(PermissionCheckRequest.class);
        AtomicReference<Map<String, Object>> seenByTool = new AtomicReference<Map<String, Object>>();
        register("shell", request -> {
            seenByTool.set(request.getArguments());
            return new ToolCallResult("shell", "ok");
        });

        // When
        executor.execute(session, null, "c1", "shell", args("command", "rm -rf /"), null);

        // Then：权限判定与工具看到的都是变换后的那一份
        verify(permissionManager).decide(captor.capture());
        assertEquals("ls -l", captor.getValue().getArguments().get("command"));
        assertEquals("ls -l", seenByTool.get().get("command"));
    }

    @Test
    void execute_should_show_transformed_arguments_to_listener() {
        // Given：轨迹行与 --show-tool-args 读的就是这个回调，展示的必须与执行的是同一份
        givenPermitted();
        onArguments(1, request -> ToolArgumentDecision.replace(args("command", "ls -l")));
        register("shell", request -> new ToolCallResult("shell", "ok"));
        ReActListener listener = mock(ReActListener.class);

        // When
        executor.execute(session, null, "c1", "shell", args("command", "rm -rf /"), listener);

        // Then
        verify(listener).onToolCallStarted("c1", "shell", args("command", "ls -l"));
    }

    @Test
    void execute_should_chain_argument_handlers_in_order() {
        // Given：order 升序；后一个处理器看到的是前一个产出的值
        givenPermitted();
        AtomicReference<Object> seenBySecond = new AtomicReference<Object>();
        onArguments(1, request -> ToolArgumentDecision.replace(args("command", "step-1")));
        onArguments(2, request -> {
            seenBySecond.set(request.getArguments().get("command"));
            return ToolArgumentDecision.abstain();
        });
        register("shell", request -> new ToolCallResult("shell", request.getArguments().get("command")));

        // When
        ToolCallResult result = executor.execute(session, null, "c1", "shell", args("command", "原始"), null);

        // Then：ABSTAIN 保持当前值，因此工具拿到的仍是第一个处理器写下的值
        assertEquals("step-1", seenBySecond.get());
        assertEquals("step-1", result.getOutput());
    }

    @Test
    void execute_should_short_circuit_when_argument_handler_denies() {
        // Given
        givenPermitted();
        AtomicReference<Boolean> laterInvoked = new AtomicReference<Boolean>(Boolean.FALSE);
        AtomicReference<Boolean> toolInvoked = new AtomicReference<Boolean>(Boolean.FALSE);
        onArguments(1, request -> ToolArgumentDecision.deny("命令里含密钥"));
        onArguments(2, request -> {
            laterInvoked.set(Boolean.TRUE);
            return ToolArgumentDecision.abstain();
        });
        register("shell", request -> {
            toolInvoked.set(Boolean.TRUE);
            return new ToolCallResult("shell", "ok");
        });

        // When
        ToolCallResult result = executor.execute(session, null, "c1", "shell", args(), null);

        // Then：短路、且恰好一条结果（配对规则不可违反）
        assertFalse(laterInvoked.get());
        assertFalse(toolInvoked.get());
        assertTrue(String.valueOf(result.getOutput()).contains("插件拒绝参数"));
        assertTrue(String.valueOf(result.getOutput()).contains("命令里含密钥"));
    }

    @Test
    void execute_should_mark_terminal_rejected_when_argument_handler_denies() {
        // Given：REJECTED 与 FAILED 的区别是「工具压根没跑」与「工具自己没成」
        givenPermitted();
        onArguments(1, request -> ToolArgumentDecision.deny("命令里含密钥"));

        // When
        ToolCallResult result = executor.execute(session, null, "c1", "shell", args(), null);

        // Then
        assertEquals(ToolMetadata.TERMINAL_REJECTED, result.getMetadata().get(ToolMetadata.KEY_TERMINAL));
        assertTrue(ToolMetadata.failed(result.getMetadata()));
    }

    @Test
    void execute_should_skip_permission_check_and_audit_when_argument_handler_denies() {
        // Given：参数被拒不是权限结论，因此不得进权限审计（审计真源仍是 PermissionDecidedEvent）
        onArguments(1, request -> ToolArgumentDecision.deny("命令里含密钥"));

        // When
        executor.execute(session, null, "c1", "shell", args(), null);

        // Then
        verify(permissionManager, never()).decide(any(PermissionCheckRequest.class));
    }

    @Test
    void execute_should_treat_argument_handler_failure_as_abstain() {
        // Given：同步侧没有护栏，异常处置是调用点的责任；插件坏掉时既不能放行也不能崩
        givenPermitted();
        onArguments(1, request -> {
            throw new IllegalStateException("插件崩了");
        });
        register("shell", request -> new ToolCallResult("shell", request.getArguments().get("command")));

        // When
        ToolCallResult result = executor.execute(session, null, "c1", "shell", args("command", "echo hi"), null);

        // Then：参数保持原样，工具照常执行
        assertEquals("echo hi", result.getOutput());
    }

    @Test
    void execute_should_adjust_result_before_limiting() {
        // Given：上限 40；工具原本返回超限文本，插件把它换成另一个超限文本
        when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings(null, null, 40, null, null, null));
        givenPermitted();
        String original = repeat("A", 200);
        String adjusted = repeat("B", 300);
        register("read_file", request -> new ToolCallResult("read_file", original));
        onResult(1, request -> ToolResultAdjustment.of(adjusted, null));

        // When
        executor.execute(session, null, "c1", "read_file", args(), null);

        // Then：落盘（也就是被截断）的是变换后的那一份。若变换排在 limit 之后，
        // 这里落下去的就会是 original，而信封里的长度也会按 original 算
        verify(store).store(session.getSessionId(), "c1", "read_file", adjusted, false);
    }

    @Test
    void execute_should_keep_output_type_when_result_handler_replaces() {
        // Given：工具返回字符串，插件换成结构化对象——limit 按对象路径序列化，说明变换在序列化之前
        givenPermitted();
        register("shell", request -> new ToolCallResult("shell", "plain"));
        onResult(1, request -> ToolResultAdjustment.of(args("k", "v"), null));

        // When
        ToolCallResult result = executor.execute(session, null, "c1", "shell", args(), null);

        // Then
        assertEquals("{\"k\":\"v\"}", result.getOutput());
    }

    @Test
    void execute_should_chain_result_handlers_keeping_output_and_metadata() {
        // Given：一个处理器只改输出、后一个只改元数据，两者必须同时生效
        givenPermitted();
        register("shell", request -> new ToolCallResult("shell", "原始"));
        onResult(1, request -> ToolResultAdjustment.of("脱敏后", null));
        onResult(2, request -> ToolResultAdjustment.metadataOnly(args("audit", "yes")));

        // When
        ToolCallResult result = executor.execute(session, null, "c1", "shell", args(), null);

        // Then
        assertEquals("脱敏后", result.getOutput());
        assertEquals("yes", result.getMetadata().get("audit"));
    }

    @Test
    void execute_should_expose_object_output_to_later_result_handler() {
        // Given：output 在该步不得被序列化成文本，否则第二个处理器看到的就是 JSON 字符串
        givenPermitted();
        register("shell", request -> new ToolCallResult("shell", args("k", "v")));
        AtomicReference<Object> seen = new AtomicReference<Object>();
        onResult(1, request -> {
            seen.set(request.getOutput());
            return ToolResultAdjustment.abstain();
        });

        // When
        executor.execute(session, null, "c1", "shell", args(), null);

        // Then
        assertInstanceOf(Map.class, seen.get());
    }

    @Test
    void execute_should_expose_transformed_arguments_and_failed_flag_to_result_handler() {
        // Given：工具抛错（failed 为真）且参数已被改写
        givenPermitted();
        onArguments(1, request -> ToolArgumentDecision.replace(args("path", "/safe")));
        register("read_file", request -> {
            throw new JellyfishException("文件不存在: /safe");
        });
        AtomicReference<Boolean> failed = new AtomicReference<Boolean>();
        AtomicReference<Object> path = new AtomicReference<Object>();
        onResult(1, request -> {
            failed.set(Boolean.valueOf(request.isFailed()));
            path.set(request.getArguments().get("path"));
            return ToolResultAdjustment.abstain();
        });

        // When
        executor.execute(session, null, "c1", "read_file", args("path", "/unsafe"), null);

        // Then：判据与界面将要用的是同一个（ToolMetadata.failed），参数是实际执行的那一份
        assertTrue(failed.get());
        assertEquals("/safe", path.get());
    }

    @Test
    void execute_should_treat_result_handler_failure_as_abstain() {
        // Given
        givenPermitted();
        register("shell", request -> new ToolCallResult("shell", "ok"));
        onResult(1, request -> {
            throw new IllegalStateException("插件崩了");
        });

        // When
        ToolCallResult result = executor.execute(session, null, "c1", "shell", args(), null);

        // Then：结果保持原样，调用照常返回
        assertEquals("ok", result.getOutput());
    }

    @Test
    void execute_should_pass_original_output_when_no_result_handler_registered() {
        // Given：0 handler 时结果不进任何变换，元数据也不被重建
        givenPermitted();
        Map<String, Object> metadata = args("summary", "一次调用");
        register("shell", request -> new ToolCallResult("shell", args("k", "v"), metadata));

        // When
        ToolCallResult result = executor.execute(session, null, "c1", "shell", args(), null);

        // Then
        assertEquals("{\"k\":\"v\"}", result.getOutput());
        assertEquals("一次调用", result.getMetadata().get("summary"));
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
     * 注册一个参数改写处理器。
     *
     * @param order   顺序
     * @param handler 处理器
     */
    private void onArguments(int order, ExtensionHandler<ToolArgumentPreRequest, ToolArgumentDecision> handler) {
        extensions.contribute("test", ToolArgumentPreRequest.class, null, handler, RegisterOptions.order(order));
    }

    /**
     * 注册一个结果整形处理器。
     *
     * @param order   顺序
     * @param handler 处理器
     */
    private void onResult(int order, ExtensionHandler<ToolResultPostRequest, ToolResultAdjustment> handler) {
        extensions.contribute("test", ToolResultPostRequest.class, null, handler, RegisterOptions.order(order));
    }

    /**
     * 让权限判定放行。
     */
    private void givenPermitted() {
        lenient().when(permissionManager.decide(any(PermissionCheckRequest.class)))
                .thenReturn(PermissionDecision.allow(null));
    }

    /**
     * 重复一个字符若干次。
     *
     * @param text  字符
     * @param times 次数
     * @return 重复后的文本
     */
    private static String repeat(String text, int times) {
        StringBuilder builder = new StringBuilder(text.length() * times);
        for (int i = 0; i < times; i++) {
            builder.append(text);
        }
        return builder.toString();
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
