package zcd.jellyfish.core.input;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.InputDirectiveDescriptor;
import zcd.jellyfish.api.extension.InputDirectiveRequest;
import zcd.jellyfish.api.extension.InputDirectiveResult;
import zcd.jellyfish.api.extension.InputReferenceChoice;
import zcd.jellyfish.api.extension.InputReferenceDescriptor;
import zcd.jellyfish.api.extension.InputReferenceRequest;
import zcd.jellyfish.api.extension.InputReferenceResult;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.PermissionDecision;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.core.ReActListener;
import zcd.jellyfish.core.runtime.RunContextHolder;
import zcd.jellyfish.core.tool.ToolExecutor;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.ReactSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.permission.PermissionManager;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionDefaults;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.infra.session.SessionMessage;
import zcd.jellyfish.infra.tooloutput.ToolOutputLimiter;
import zcd.jellyfish.infra.tooloutput.ToolOutputStore;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link InputDirectives} 的单元测试：验证解析、执行落会话与行内引用补全的片段切分。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class InputDirectivesTest {

    /** 权限管理器。 */
    @Mock
    private PermissionManager permissionManager;

    /** 通知发布入口。 */
    @Mock
    private EventPublisher events;

    /** 运行时配置门面。 */
    @Mock
    private RuntimeConfig runtimeConfig;

    /** agent 门面。 */
    @Mock
    private AgentManager agentManager;

    /** 真实同步扩展点策略。 */
    private ExtensionRegistry extensions;

    /** 真实会话服务。 */
    private SessionManager sessionManager;

    /** 专用单线程执行器，便于用 await 汇合。 */
    private ExecutorService executor;

    /** 被测服务。 */
    private InputDirectives directives;

    /** 会话。 */
    private Session session;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        extensions = new ExtensionRegistry(new TypeRegistry());
        sessionManager = new SessionManager(agentManager, events, extensions, new SessionDefaults());
        session = sessionManager.createDefault();
        ToolOutputLimiter outputLimiter = new ToolOutputLimiter(runtimeConfig, new ToolOutputStore(runtimeConfig));
        lenient().when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        directives = new InputDirectives(extensions, new ToolExecutor(permissionManager, extensions, events,
                outputLimiter, new RunContextHolder()), sessionManager, executor);
    }

    @AfterEach
    void tearDown() {
        directives.close();
    }

    @Test
    void resolve_without_handler_should_return_empty() {
        // When
        Optional<InputDirectiveCall> call = directives.resolve(session.getSessionId(), "!ls");

        // Then
        assertFalse(call.isPresent());
    }

    @Test
    void resolve_should_return_empty_when_handler_unclaims() {
        // Given
        registerDirective("!", request -> InputDirectiveResult.unclaimed());

        // When
        Optional<InputDirectiveCall> call = directives.resolve(session.getSessionId(), "!ls");

        // Then
        assertFalse(call.isPresent());
    }

    @Test
    void resolve_should_expose_marker_tool_and_input() {
        // Given
        registerDirective("!", request -> InputDirectiveResult.toolCall("shell",
                Collections.<String, Object>singletonMap("command", "ls")));

        // When
        Optional<InputDirectiveCall> call = directives.resolve(session.getSessionId(), "  !ls  ");

        // Then
        assertTrue(call.isPresent());
        assertEquals("!", call.get().getMarker());
        assertEquals("shell", call.get().getToolName());
        assertEquals("!ls", call.get().getInput());
    }

    @Test
    void resolve_should_return_empty_when_input_is_blank() {
        // When / Then
        assertFalse(directives.resolve(session.getSessionId(), "   ").isPresent());
        assertFalse(directives.resolve(session.getSessionId(), null).isPresent());
    }

    @Test
    void start_should_run_tool_and_append_user_message() throws Exception {
        // Given
        when(permissionManager.decide(any(PermissionCheckRequest.class))).thenReturn(PermissionDecision.allow(null));
        registerTool("shell", request -> new ToolCallResult("shell", "total 0"));
        registerDirective("!", request -> InputDirectiveResult.toolCall("shell",
                Collections.<String, Object>singletonMap("command", "ls")));
        InputDirectiveCall call = directives.resolve(session.getSessionId(), "!ls").orElseThrow(AssertionError::new);

        // When
        InputDirectiveRun run = directives.start(session.getSessionId(), call, ReActListener.NOOP);
        assertNotNull(run);
        awaitMessages(1);

        // Then
        SessionMessage message = session.getMessages().get(0);
        assertEquals(LlmMessage.ROLE_USER, message.getRole());
        assertTrue(message.getMessage().getContent().startsWith("[手动执行] $ ls"));
        assertFalse(message.getMessage().getContent().contains("$ !"), "回显不应带输入框语法里的标记");
        assertTrue(message.getMessage().getContent().contains("total 0"));
    }

    @Test
    void complete_without_marker_should_return_empty() {
        // When
        InputReferenceCompletion completion = directives.complete("hello world", 5, session.getSessionId());

        // Then
        assertFalse(completion.isPresent());
    }

    @Test
    void complete_should_extract_token_and_offsets() {
        // Given
        registerReference("@", request -> InputReferenceResult.of(Arrays.asList(
                new InputReferenceChoice(request.getToken() + "x.txt", null, null))));

        // When：光标停在「看看 @sr|c」中间，片段仍是 @src
        InputReferenceCompletion completion = directives.complete("看看 @src 结尾", 7, session.getSessionId());

        // Then
        assertTrue(completion.isPresent());
        assertEquals("@", completion.getMarker());
        assertEquals(3, completion.getReplaceStart());
        assertEquals(7, completion.getReplaceEnd());
        assertEquals(1, completion.getChoices().size());
        assertEquals("srcx.txt", completion.getChoices().get(0).getLabel());
    }

    @Test
    void complete_should_return_empty_choices_when_no_match() {
        // Given
        registerReference("@", request -> InputReferenceResult.empty());

        // When
        InputReferenceCompletion completion = directives.complete("@zzz", 4, session.getSessionId());

        // Then
        assertTrue(completion.isPresent());
        assertTrue(completion.getChoices().isEmpty());
    }

    @Test
    void complete_should_ignore_unregistered_marker() {
        // Given：只注册了 @，线段却是 #abc
        registerReference("@", request -> InputReferenceResult.empty());

        // When
        InputReferenceCompletion completion = directives.complete("#abc", 4, session.getSessionId());

        // Then
        assertFalse(completion.isPresent());
    }

    @Test
    void complete_should_swallow_handler_failure() {
        // Given
        registerReference("@", request -> {
            throw new IllegalStateException("插件坏了");
        });

        // When
        InputReferenceCompletion completion = directives.complete("@a", 2, session.getSessionId());

        // Then：补全失败退化成未命中，不得把界面弄崩
        assertFalse(completion.isPresent());
    }

    /**
     * 等待会话消息数量达到预期。
     *
     * @param expected 期望条数
     * @throws InterruptedException 等待被中断时抛出
     */
    private void awaitMessages(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (session.getMessages().size() >= expected) {
                return;
            }
            Thread.sleep(10L);
        }
        assertEquals(expected, session.getMessages().size());
    }

    /**
     * 注册一条输入指令。
     *
     * @param marker  标记
     * @param handler 处理器
     */
    private void registerDirective(String marker, ExtensionHandler<InputDirectiveRequest, InputDirectiveResult>
                                           handler) {
        extensions.handle("test", InputDirectiveRequest.class, marker,
                new InputDirectiveDescriptor("test"), handler, RegisterOptions.DEFAULT);
    }

    /**
     * 注册一条引用补全。
     *
     * @param marker  标记
     * @param handler 处理器
     */
    private void registerReference(String marker, ExtensionHandler<InputReferenceRequest, InputReferenceResult>
                                           handler) {
        extensions.handle("test", InputReferenceRequest.class, marker,
                new InputReferenceDescriptor("test"), handler, RegisterOptions.DEFAULT);
    }

    /**
     * 注册一个测试工具。
     *
     * @param name    工具名
     * @param handler 处理器
     */
    private void registerTool(String name, ExtensionHandler<ToolCallRequest, ToolCallResult> handler) {
        extensions.handle("test", ToolCallRequest.class, name, null, handler, RegisterOptions.DEFAULT);
    }
}
