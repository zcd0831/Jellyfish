package zcd.jellyfish.infra.event;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.extension.ExtensionRequest;
import zcd.jellyfish.api.extension.ExtensionException;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.infra.event.callback.CallbackRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CallbackDispatcher} 的单元测试：验证派发路径、多处理器按序调用、失败即停与失败记账。
 *
 * @author zcd
 */
class CallbackDispatcherTest {

    /** 回调注册表。 */
    private final CallbackRegistry registry = new CallbackRegistry();

    /** 回调应答槽。 */
    private final CallbackReplies replies = new CallbackReplies();

    /** 指标。 */
    private final EventBusStats stats = new EventBusStats();

    /** 被测回调分发器。 */
    private final CallbackDispatcher dispatcher = new CallbackDispatcher(registry, replies, stats);

    @Test
    void onToolCall_should_complete_result_and_count_dispatched() {
        // Given
        registry.register("builtin", ToolCallRequest.class, "calculator",
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
    void onCommandRequest_should_complete_result_when_handler_registered() {
        // Given
        registry.register("builtin", CommandRequest.class, "calculator", callback -> "42",
                RegisterOptions.DEFAULT);
        CommandRequest callback = new CommandRequest("calculator", Object.class, null);
        replies.open(callback);

        // When
        dispatcher.onCommandRequest(callback);

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
        ExtensionException exception = assertThrows(ExtensionException.class, () -> replies.await(callback));
        assertEquals(ExtensionException.Code.NO_HANDLER, exception.getCode());
        assertEquals(1L, stats.getNoHandlerCallbacks());
        assertEquals(1L, stats.getFailedCallbacks());
    }

    @Test
    void dispatchCallback_should_call_every_matching_handler_in_order() {
        // Given：类型级贡献与路由键处理器同时命中，两者都要被调用
        registry.contribute("plugin-a", ToolCallRequest.class,
                callback -> new ToolCallResult("calculator", 1), RegisterOptions.order(2));
        registry.register("plugin-b", ToolCallRequest.class, "calculator",
                callback -> new ToolCallResult("calculator", 2), RegisterOptions.order(1));
        ToolCallRequest callback = toolCall();
        replies.open(callback);

        // When
        dispatcher.onToolCall(callback);

        // Then
        assertEquals(2L, stats.getDispatchedCallbacks());
        assertEquals(2, replies.await(callback).getOutput());
    }

    @Test
    void dispatchCallback_should_aggregate_through_callback_owned_container() {
        // Given：需要聚合多个结果的调用点让回调自带容器承接
        registry.contribute("late", CollectRequest.class, callback -> {
            callback.add("late");
            return "late";
        }, RegisterOptions.order(2));
        registry.contribute("early", CollectRequest.class, callback -> {
            callback.add("early");
            return "early";
        }, RegisterOptions.order(1));
        CollectRequest callback = new CollectRequest();
        replies.open(callback);

        // When
        dispatcher.dispatchCallback(callback);

        // Then
        assertEquals(Arrays.asList("early", "late"), callback.fragments());
        assertEquals("early", replies.await(callback));
    }

    @Test
    void dispatchCallback_should_stop_after_first_failure() {
        // Given
        List<String> invoked = new ArrayList<>();
        registry.contribute("broken", CollectRequest.class, callback -> {
            invoked.add("broken");
            throw new IllegalStateException("boom");
        }, RegisterOptions.order(1));
        registry.contribute("healthy", CollectRequest.class, callback -> {
            invoked.add("healthy");
            return "ok";
        }, RegisterOptions.order(2));
        CollectRequest callback = new CollectRequest();
        replies.open(callback);

        // When
        dispatcher.dispatchCallback(callback);

        // Then：首个处理器失败即停止，后续处理器不再执行
        assertEquals(Collections.singletonList("broken"), invoked);
        assertThrows(IllegalStateException.class, () -> replies.await(callback));
        assertEquals(1L, stats.getFailedCallbacks());
    }

    @Test
    void onToolCall_should_rethrow_and_count_when_handler_throws() {
        // Given
        registry.register("builtin", ToolCallRequest.class, "calculator", callback -> {
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
        registry.register("builtin", ToolCallRequest.class, "calculator",
                (ExtensionHandler) callback -> "not-a-tool-call-result", RegisterOptions.DEFAULT);
        ToolCallRequest callback = toolCall();
        replies.open(callback);

        // When
        dispatcher.onToolCall(callback);

        // Then
        ExtensionException exception = assertThrows(ExtensionException.class, () -> replies.await(callback));
        assertEquals(ExtensionException.Code.RESULT_TYPE_MISMATCH, exception.getCode());
        assertEquals(1L, stats.getFailedCallbacks());
    }

    @Test
    void dispatchCallback_should_run_handler_on_caller_thread() {
        // Given：通道一律在调用线程内联执行，处理器不需要（也不应）依赖隔离线程
        List<String> handlerThreads = new ArrayList<>();
        registry.contribute("inline", CollectRequest.class, callback -> {
            handlerThreads.add(Thread.currentThread().getName());
            return "ok";
        }, RegisterOptions.DEFAULT);
        CollectRequest callback = new CollectRequest();
        replies.open(callback);

        // When
        dispatcher.dispatchCallback(callback);

        // Then
        assertEquals(1, handlerThreads.size());
        assertSame(Thread.currentThread().getName(), handlerThreads.get(0));
    }

    @Test
    void dispatchCallback_should_count_no_handler_for_contribution_point_without_handlers() {
        // Given
        CollectRequest callback = new CollectRequest();
        replies.open(callback);

        // When
        dispatcher.dispatchCallback(callback);

        // Then
        assertEquals(1L, stats.getNoHandlerCallbacks());
        assertTrue(callback.fragments().isEmpty());
    }

    /**
     * 构造工具调用命令。
     *
     * @return 工具调用命令
     */
    private static ToolCallRequest toolCall() {
        return new ToolCallRequest("calculator", Collections.<String, Object>emptyMap());
    }

    /**
     * 测试用收集式回调：自带结果容器，多个处理器依次往里写。
     *
     * @author zcd
     */
    private static final class CollectRequest extends ExtensionRequest<String> {

        /** 结果容器。 */
        private final List<String> fragments = new ArrayList<>();

        /** 构造测试回调。 */
        private CollectRequest() {
            super(String.class, null);
        }

        /**
         * 追加一个片段。
         *
         * @param fragment 片段
         */
        private void add(String fragment) {
            fragments.add(fragment);
        }

        /**
         * 获取已收集的片段。
         *
         * @return 片段列表
         */
        private List<String> fragments() {
            return fragments;
        }

        @Override
        public String getRouteKey() {
            return null;
        }
    }
}
