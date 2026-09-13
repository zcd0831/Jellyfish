package zcd.jellyfish.infra.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.callback.Callback;
import zcd.jellyfish.api.event.callback.CallbackException;
import zcd.jellyfish.api.event.callback.CallbackHandler;
import zcd.jellyfish.api.event.callback.ExtensionPoint;
import zcd.jellyfish.api.event.callback.ExtensionShape;
import zcd.jellyfish.api.event.callback.PermissionCheckRequest;
import zcd.jellyfish.api.event.callback.PermissionDecision;
import zcd.jellyfish.api.event.callback.PluginRequest;
import zcd.jellyfish.api.event.callback.ToolCallRequest;
import zcd.jellyfish.api.event.callback.ToolCallResult;
import zcd.jellyfish.infra.event.callback.CallbackRegistry;
import zcd.jellyfish.infra.event.callback.ExtensionPointRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CallbackDispatcher} 的单元测试：验证三类核心回调的注册表派发、单/多结果分叉、失败语义与失败记账。
 *
 * @author zcd
 */
class CallbackDispatcherTest {

    /** 回调注册表。 */
    private final CallbackRegistry registry = new CallbackRegistry(ExtensionPointRegistry.withBuiltIns());

    /** 回调应答槽。 */
    private final CallbackReplies replies = new CallbackReplies();

    /** 指标。 */
    private final EventBusStats stats = new EventBusStats();

    /** ISOLATED 执行器。 */
    private final CallbackExecutor executor = new CallbackExecutor(EventBusOptions.defaults(), stats);

    /** 被测回调分发器。 */
    private final CallbackDispatcher dispatcher = new CallbackDispatcher(registry, replies, executor, stats);

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void onToolCall_should_complete_result_and_count_dispatched() {
        // Given
        registry.register("builtin", false, ToolCallRequest.class, "calculator",
                callback -> new ToolCallResult("calculator", 42), RegisterOptions.DEFAULT);
        ToolCallRequest callback = toolCall();
        replies.open(callback);

        // When
        dispatcher.onToolCall(callback);

        // Then
        assertEquals(42, replies.await(callback).getOutput());
        assertEquals(1L, stats.getDispatchedCallbacks());
    }

    @Test
    void onPermissionCheck_should_complete_result_when_handler_registered() {
        // Given
        registry.register("builtin", false, PermissionCheckRequest.class, null,
                callback -> PermissionDecision.allow("ok"), RegisterOptions.DEFAULT);
        PermissionCheckRequest callback = new PermissionCheckRequest("agent", "calculator", null);
        replies.open(callback);

        // When
        dispatcher.onPermissionCheck(callback);

        // Then
        assertEquals(true, replies.await(callback).isGranted());
        assertEquals(1L, stats.getDispatchedCallbacks());
    }

    @Test
    void onPluginRequest_should_complete_result_when_handler_registered() {
        // Given
        registry.register("builtin", false, PluginRequest.class, "calculator", callback -> "42",
                RegisterOptions.DEFAULT);
        PluginRequest callback = new PluginRequest("calculator", Object.class, null);
        replies.open(callback);

        // When
        dispatcher.onPluginRequest(callback);

        // Then
        assertEquals("42", replies.await(callback));
        assertEquals(1L, stats.getDispatchedCallbacks());
    }

    @Test
    void onToolCall_should_fail_with_no_handler_when_not_registered() {
        // Given
        ToolCallRequest callback = toolCall();
        replies.open(callback);

        // When
        dispatcher.onToolCall(callback);

        // Then
        CallbackException exception = assertThrows(CallbackException.class, () -> replies.await(callback));
        assertEquals(CallbackException.Code.NO_HANDLER, exception.getCode());
        assertEquals(1L, stats.getNoHandlerCallbacks());
        assertEquals(1L, stats.getFailedCallbacks());
    }

    @Test
    void onToolCall_should_fail_with_ambiguous_handler_when_two_candidates_match() {
        // Given：类型唯一处理器与路由处理器同时命中
        registry.register("builtin", false, ToolCallRequest.class, null,
                callback -> new ToolCallResult("calculator", 1), RegisterOptions.DEFAULT);
        registry.register("plugin-a", false, ToolCallRequest.class, "calculator",
                callback -> new ToolCallResult("calculator", 2), RegisterOptions.DEFAULT);
        ToolCallRequest callback = toolCall();
        replies.open(callback);

        // When
        dispatcher.onToolCall(callback);

        // Then
        CallbackException exception = assertThrows(CallbackException.class, () -> replies.await(callback));
        assertEquals(CallbackException.Code.AMBIGUOUS_HANDLER, exception.getCode());
        assertEquals(1L, stats.getAmbiguousHandlerCallbacks());
    }

    @Test
    void onToolCall_should_rethrow_and_count_when_handler_throws() {
        // Given
        registry.register("builtin", false, ToolCallRequest.class, "calculator", callback -> {
            throw new IllegalStateException("boom");
        }, RegisterOptions.DEFAULT);
        ToolCallRequest callback = toolCall();
        replies.open(callback);

        // When
        dispatcher.onToolCall(callback);

        // Then
        assertThrows(IllegalStateException.class, () -> replies.await(callback));
        assertEquals(1L, stats.getFailedCallbacks());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void onToolCall_should_fail_with_result_type_mismatch_when_handler_returns_wrong_type() {
        // Given：用原始类型绕过编译期泛型，模拟处理器返回与 resultType 不符的对象
        registry.register("builtin", false, ToolCallRequest.class, "calculator",
                (CallbackHandler) callback -> "not-a-tool-call-result", RegisterOptions.DEFAULT);
        ToolCallRequest callback = toolCall();
        replies.open(callback);

        // When
        dispatcher.onToolCall(callback);

        // Then
        CallbackException exception = assertThrows(CallbackException.class, () -> replies.await(callback));
        assertEquals(CallbackException.Code.RESULT_TYPE_MISMATCH, exception.getCode());
        assertEquals(1L, stats.getFailedCallbacks());
    }

    @Test
    void dispatchCallback_should_collect_all_results_in_order_when_shape_is_contribute() {
        // Given
        CallbackRegistry nonUnique = registryWith(ContributionRequest.class);
        CallbackDispatcher manyDispatcher = new CallbackDispatcher(nonUnique, replies, executor, stats);
        nonUnique.register("late", false, ContributionRequest.class, null, callback -> "late",
                RegisterOptions.order(2));
        nonUnique.register("early", false, ContributionRequest.class, null, callback -> "early",
                RegisterOptions.order(1));
        ContributionRequest callback = new ContributionRequest();
        replies.open(callback);

        // When
        manyDispatcher.dispatchCallback(callback);

        // Then
        assertEquals(Arrays.asList("early", "late"), replies.awaitAll(callback));
        assertEquals(2L, stats.getDispatchedCallbacks());
    }

    @Test
    void dispatchCallback_should_drop_failing_handler_when_fail_open() {
        // Given：A 贡献为 fail-open，单个处理器异常只丢弃该结果
        CallbackRegistry nonUnique = registryWith(ContributionRequest.class);
        CallbackDispatcher manyDispatcher = new CallbackDispatcher(nonUnique, replies, executor, stats);
        nonUnique.register("broken", false, ContributionRequest.class, null, callback -> {
            throw new IllegalStateException("boom");
        }, RegisterOptions.order(1));
        nonUnique.register("healthy", false, ContributionRequest.class, null, callback -> "ok",
                RegisterOptions.order(2));
        ContributionRequest callback = new ContributionRequest();
        replies.open(callback);

        // When
        manyDispatcher.dispatchCallback(callback);

        // Then
        assertEquals(Collections.singletonList("ok"), replies.awaitAll(callback));
        assertEquals(1L, stats.getFailedCallbacks());
    }

    @Test
    void dispatchCallback_should_fail_whole_when_fail_closed() {
        // Given：E 策略为 fail-closed，单个处理器异常即整次失败
        CallbackRegistry nonUnique = registryWith(PolicyRequest.class);
        CallbackDispatcher manyDispatcher = new CallbackDispatcher(nonUnique, replies, executor, stats);
        nonUnique.register("broken", false, PolicyRequest.class, null, callback -> {
            throw new IllegalStateException("boom");
        }, RegisterOptions.order(1));
        nonUnique.register("ignored", false, PolicyRequest.class, null, callback -> "ok",
                RegisterOptions.order(2));
        PolicyRequest callback = new PolicyRequest();
        replies.open(callback);

        // When
        manyDispatcher.dispatchCallback(callback);

        // Then
        assertThrows(IllegalStateException.class, () -> replies.awaitAll(callback));
        assertEquals(1L, stats.getFailedCallbacks());
        assertEquals(0L, stats.getDispatchedCallbacks());
    }

    @Test
    void dispatchCallback_should_complete_null_when_optional_and_no_handler() {
        // Given：空表策略 OPTIONAL，单结果形状以 null 区分于「未应答」
        CallbackRegistry optional = registryWith(OptionalSingleRequest.class);
        CallbackDispatcher optionalDispatcher = new CallbackDispatcher(optional, replies, executor, stats);
        OptionalSingleRequest callback = new OptionalSingleRequest();
        replies.open(callback);

        // When
        optionalDispatcher.dispatchCallback(callback);

        // Then
        assertEquals(null, replies.await(callback));
        assertEquals(1L, stats.getNoHandlerCallbacks());
        assertEquals(0L, stats.getFailedCallbacks());
    }

    @Test
    void dispatchCallback_should_run_handler_on_isolated_thread_when_execution_is_isolated() {
        // Given
        CallbackRegistry nonUnique = registryWith(ContributionRequest.class);
        CallbackDispatcher manyDispatcher = new CallbackDispatcher(nonUnique, replies, executor, stats);
        String callerThread = Thread.currentThread().getName();
        List<String> handlerThreads = new ArrayList<>();
        nonUnique.register("isolated", false, ContributionRequest.class, null, callback -> {
            handlerThreads.add(Thread.currentThread().getName());
            return "ok";
        }, RegisterOptions.DEFAULT);
        ContributionRequest callback = new ContributionRequest();
        replies.open(callback);

        // When
        manyDispatcher.dispatchCallback(callback);

        // Then
        assertEquals(Collections.singletonList("ok"), replies.awaitAll(callback));
        assertEquals(1, handlerThreads.size());
        assertNotEquals(callerThread, handlerThreads.get(0));
        assertTrue(handlerThreads.get(0).startsWith(EventThreadFactory.CALLBACK_PREFIX));
    }

    /**
     * 构造额外登记指定回调类型的注册表。
     *
     * @param callbackType 额外登记的回调类型
     * @return 回调注册表
     */
    private static CallbackRegistry registryWith(Class<? extends Callback<?>> callbackType) {
        ExtensionPointRegistry definitions = ExtensionPointRegistry.withBuiltIns();
        definitions.register(callbackType);
        return new CallbackRegistry(definitions);
    }

    /**
     * 构造工具调用命令。
     *
     * @return 工具调用命令
     */
    private static ToolCallRequest toolCall() {
        return new ToolCallRequest("calculator", Collections.<String, Object>emptyMap(), null, 0L);
    }

    /**
     * 测试用 A 贡献回调。
     *
     * @author zcd
     */
    @ExtensionPoint(id = "test.contribute", shape = ExtensionShape.CONTRIBUTE)
    private static final class ContributionRequest extends Callback<String> {

        /** 构造测试回调。 */
        private ContributionRequest() {
            super(String.class, null, 0L);
        }

        @Override
        public String getRouteKey() {
            return null;
        }
    }

    /**
     * 测试用 E 策略回调。
     *
     * @author zcd
     */
    @ExtensionPoint(id = "test.policy", shape = ExtensionShape.POLICY)
    private static final class PolicyRequest extends Callback<String> {

        /** 构造测试回调。 */
        private PolicyRequest() {
            super(String.class, null, 0L);
        }

        @Override
        public String getRouteKey() {
            return null;
        }
    }

    /**
     * 测试用「B 形状但空表可容忍」回调。
     *
     * @author zcd
     */
    @ExtensionPoint(id = "test.optional", shape = ExtensionShape.PROVIDE,
            emptyPolicy = ExtensionPoint.EmptyPolicy.OPTIONAL)
    private static final class OptionalSingleRequest extends Callback<String> {

        /** 构造测试回调。 */
        private OptionalSingleRequest() {
            super(String.class, null, 0L);
        }

        @Override
        public String getRouteKey() {
            return null;
        }
    }
}
