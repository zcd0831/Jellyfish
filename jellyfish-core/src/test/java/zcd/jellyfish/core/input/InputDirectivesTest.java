package zcd.jellyfish.core.input;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.JellyfishException;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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

    /** 工具执行器：权限与截断的唯一入口（与生产装配同一形状）。 */
    private ToolExecutor toolExecutor;

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
        toolExecutor = new ToolExecutor(permissionManager, extensions, events, outputLimiter, new RunContextHolder());
        directives = new InputDirectives(extensions, toolExecutor, sessionManager, executor);
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
        InputDirectiveRun run = directives.start(session.getSessionId(), call, ReActListener.NOOP,
                finished -> { });
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
    void complete_should_keepEscapedWhitespaceInsideToken() {
        // Given
        registerReference("@", request -> InputReferenceResult.of(Arrays.asList(
                new InputReferenceChoice("占位", null, null))));

        // When：文件名叫「my file.txt」，补全插进去的是转义过的 @my\ file.txt，
        // 光标停在末尾（接着往下敲也要能继续补全）
        String input = "看看 @my\\ file.txt";
        InputReferenceCompletion completion = directives.complete(input, input.length(), session.getSessionId());

        // Then：片段必须包含整个转义过的路径，替换区间也要覆盖它
        assertTrue(completion.isPresent());
        assertEquals(3, completion.getReplaceStart());
        assertEquals(input.length(), completion.getReplaceEnd());
        assertEquals("my\\ file.txt", tokenOf(input, completion));
    }

    @Test
    void complete_should_breakAtWhitespace_when_escapeIsDoubled() {
        // Given
        registerReference("@", request -> InputReferenceResult.of(Arrays.asList(
                new InputReferenceChoice("占位", null, null))));

        // When：「\\ 」是「一个真的反斜杠 + 一个真边界」，因此片段在它那里断开，
        // 光标后面的 b 已经是另一个片段（不以标记开头）
        String input = "@a\\\\ b";
        InputReferenceCompletion completion = directives.complete(input, input.length(), session.getSessionId());

        // Then
        assertFalse(completion.isPresent());
    }

    /**
     * 取补全结果覆盖的那一段原文（去掉标记）。
     *
     * @param input      输入全文
     * @param completion 补全结果
     * @return 片段里的 token
     */
    private static String tokenOf(String input, InputReferenceCompletion completion) {
        return input.substring(completion.getReplaceStart() + 1, completion.getReplaceEnd());
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

    @Test
    void submit_should_notify_completion_once_when_tool_finishes() throws Exception {
        // Given：一条会被执行的指令（工具正常返回）
        when(permissionManager.decide(any(PermissionCheckRequest.class))).thenReturn(PermissionDecision.allow(null));
        registerTool("shell", request -> new ToolCallResult("shell", "total 0"));
        registerDirective("!", request -> InputDirectiveResult.toolCall("shell",
                Collections.<String, Object>singletonMap("command", "ls")));
        List<InputDirectiveRun> finished = Collections.synchronizedList(new ArrayList<InputDirectiveRun>());

        // When：提交（结束通知在提交之前交进去）
        Optional<InputDirectiveRun> run = directives.submit(session.getSessionId(), "!ls", ReActListener.NOOP,
                finished::add);

        // Then：恰好通知一次，且它不是被取消的那一种
        assertTrue(run.isPresent());
        awaitFinished(finished, 1);
        assertEquals(1, finished.size(), "结束通知恰好一次");
        assertFalse(finished.get(0).isCancelled());
    }

    @Test
    void submit_should_notify_completion_with_cancelled_run_when_cancelled() throws Exception {
        // Given：一条卡在工具里不返回的指令（Esc 能在它执行期间到达）
        when(permissionManager.decide(any(PermissionCheckRequest.class))).thenReturn(PermissionDecision.allow(null));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        registerTool("shell", request -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ToolCallResult("shell", "total 0");
        });
        registerDirective("!", request -> InputDirectiveResult.toolCall("shell",
                Collections.<String, Object>singletonMap("command", "ls")));
        List<InputDirectiveRun> finished = Collections.synchronizedList(new ArrayList<InputDirectiveRun>());

        Optional<InputDirectiveRun> run = directives.submit(session.getSessionId(), "!ls", ReActListener.NOOP,
                finished::add);
        assertTrue(run.isPresent());
        assertTrue(entered.await(5, TimeUnit.SECONDS), "工具应当已经开始跑");

        // When：用户按下 Esc，工具随后返回
        run.get().cancel();
        release.countDown();

        // Then：通知照发一次，并且如实是「已取消」——订阅者据此说「被打断」而不是「跑完了」
        awaitFinished(finished, 1);
        assertEquals(1, finished.size());
        assertTrue(finished.get(0).isCancelled());
    }

    @Test
    void start_should_report_queue_full_as_jellyfish_exception_without_leaking_entry() {
        // Given：执行器直接拒收（队列满）。RejectedExecutionException 不是外壳声明会处理的类型，
        // 它会一路穿过 ConversationService.submit 的契约打到界面上
        ExecutorService rejecting = mock(ExecutorService.class);
        when(rejecting.submit(any(Runnable.class))).thenThrow(new RejectedExecutionException("full"));
        InputDirectives full = new InputDirectives(extensions, toolExecutor, sessionManager, rejecting);
        registerTool("shell", request -> new ToolCallResult("shell", "total 0"));
        registerDirective("!", request -> InputDirectiveResult.toolCall("shell",
                Collections.<String, Object>singletonMap("command", "ls")));
        InputDirectiveCall call = full.resolve(session.getSessionId(), "!ls").orElseThrow(AssertionError::new);

        // When
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> full.start(session.getSessionId(), call, ReActListener.NOOP, finished -> { }));

        // Then：说清是队列满（而不是别的什么），且没把条目留在在途表里
        assertTrue(error.getMessage().contains("队列已满"), error.getMessage());
        assertEquals(0, full.activeRunCount(), "被拒的指令不该留在在途表里");
        full.close();
    }

    @Test
    void submit_should_report_done_when_executor_rejects_the_task() {
        // 任务没被受理同样是「结束」：句柄若继续回答「还没跑完」，
        // 外壳就会永远停在「运行中」，而实际上什么都不会再发生
        InputDirectiveRun run = new InputDirectiveRun("r1", "!", "!ls");
        ExecutorService rejecting = mock(ExecutorService.class);
        when(rejecting.submit(any(Runnable.class))).thenThrow(new RejectedExecutionException("full"));

        assertThrows(RejectedExecutionException.class, () -> run.submit(rejecting, () -> { }));

        assertTrue(run.isDone());
    }

    /**
     * 等待结束通知达到预期条数。
     *
     * @param finished 已收到通知的句柄
     * @param expected 期望条数
     * @throws InterruptedException 等待被中断时抛出
     */
    private static void awaitFinished(List<InputDirectiveRun> finished, int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (finished.size() >= expected) {
                return;
            }
            Thread.sleep(10L);
        }
        assertEquals(expected, finished.size());
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
