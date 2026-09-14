package zcd.jellyfish.infra.extension;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.ExtensionException;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ExtensionRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExtensionRegistry} 的单元测试：验证「只查不调」的查找入口、单处理器执行与编排归调用方。
 * <p>
 * 编排类用例（短路、链式）刻意写成调用点视角：注册表只提供有序列表与单处理器执行，
 * 循环与合并发生在测试代码里，正是内核调用点的写法。
 *
 * @author zcd
 */
class ExtensionRegistryTest {

    /** 共用注册表。 */
    private final TypeRegistry registry = new TypeRegistry();

    /** 被测同步策略。 */
    private final ExtensionRegistry extensions = new ExtensionRegistry(registry);

    @Test
    void handle_should_register_unique_handler_and_expose_it_via_handler() {
        // Given
        ExtensionHandler<CommandRequest, Object> handler = request -> "ok";

        // When
        extensions.handle("plugin-a", CommandRequest.class, "calc", "descriptor", handler, RegisterOptions.DEFAULT);

        // Then
        assertSame(handler, extensions.handler(CommandRequest.class, "calc"));
        assertEquals("descriptor", registry.registrationsOf(CommandRequest.class).get(0).getDescriptor());
    }

    @Test
    void handle_should_throw_duplicate_handler_when_key_occupied() {
        // Given
        extensions.handle("builtin", CommandRequest.class, "calc", null, request -> "one", RegisterOptions.DEFAULT);

        // When
        ExtensionException exception = assertThrows(ExtensionException.class,
                () -> extensions.handle("plugin-a", CommandRequest.class, "calc", null, request -> "two",
                        RegisterOptions.DEFAULT));

        // Then
        assertEquals(ExtensionException.Code.DUPLICATE_HANDLER, exception.getCode());
    }

    @Test
    void handle_should_replace_handler_when_override_declared() {
        // Given
        extensions.handle("builtin", CommandRequest.class, "calc", null, request -> "one", RegisterOptions.DEFAULT);
        ExtensionHandler<CommandRequest, Object> replacement = request -> "two";

        // When
        extensions.handle("plugin-a", CommandRequest.class, "calc", null, replacement,
                RegisterOptions.override(true));

        // Then
        assertSame(replacement, extensions.handler(CommandRequest.class, "calc"));
    }

    @Test
    void subscription_close_should_release_registration() {
        // Given
        Subscription handle = extensions.handle("plugin-a", CommandRequest.class, "calc", null,
                request -> "ok", RegisterOptions.DEFAULT);

        // When
        handle.close();

        // Then
        assertTrue(extensions.handlers(CommandRequest.class, "calc").isEmpty());
    }

    @Test
    void handlers_should_be_sorted_by_order_then_registration_sequence() {
        // Given：故意让后注册的 order 更小
        extensions.contribute("late", ContributionRequest.class, null, request -> "late",
                RegisterOptions.order(10));
        ExtensionHandler<ContributionRequest, String> early = request -> "early";
        extensions.contribute("early", ContributionRequest.class, null, early, RegisterOptions.order(-5));

        // When
        List<ExtensionHandler<ContributionRequest, String>> handlers =
                extensions.handlers(ContributionRequest.class, null);

        // Then
        assertEquals(2, handlers.size());
        assertSame(early, handlers.get(0));
    }

    @Test
    void handlers_should_not_call_any_handler() {
        // Given：两个处理器都会自增计数
        AtomicInteger calls = new AtomicInteger();
        extensions.contribute("a", ContributionRequest.class, null, request -> {
            calls.incrementAndGet();
            return "a";
        }, RegisterOptions.DEFAULT);
        extensions.contribute("b", ContributionRequest.class, null, request -> {
            calls.incrementAndGet();
            return "b";
        }, RegisterOptions.DEFAULT);

        // When
        extensions.handlers(ContributionRequest.class, null);

        // Then：查找本身不产生任何调用
        assertEquals(0, calls.get());
    }

    @Test
    void handlers_should_return_empty_list_when_nothing_matches() {
        // Then
        assertTrue(extensions.handlers(ContributionRequest.class, null).isEmpty());
    }

    @Test
    void handlers_should_return_unmodifiable_list() {
        // Given
        extensions.contribute("a", ContributionRequest.class, null, request -> "a", RegisterOptions.DEFAULT);

        // When
        List<ExtensionHandler<ContributionRequest, String>> handlers =
                extensions.handlers(ContributionRequest.class, null);

        // Then
        assertThrows(UnsupportedOperationException.class, handlers::clear);
    }

    @Test
    void handler_should_throw_no_handler_when_nothing_matches() {
        // When
        ExtensionException exception = assertThrows(ExtensionException.class,
                () -> extensions.handler(CommandRequest.class, "missing"));

        // Then
        assertEquals(ExtensionException.Code.NO_HANDLER, exception.getCode());
        assertTrue(exception.getMessage().contains("missing"));
    }

    @Test
    void handler_should_throw_ambiguous_handler_when_multiple_match() {
        // Given
        extensions.contribute("a", CommandRequest.class, null, request -> "a", RegisterOptions.DEFAULT);
        extensions.contribute("b", CommandRequest.class, null, request -> "b", RegisterOptions.DEFAULT);

        // When
        ExtensionException exception = assertThrows(ExtensionException.class,
                () -> extensions.handler(CommandRequest.class, null));

        // Then
        assertEquals(ExtensionException.Code.AMBIGUOUS_HANDLER, exception.getCode());
    }

    @Test
    void handler_should_not_call_the_matched_handler() {
        // Given
        AtomicInteger calls = new AtomicInteger();
        extensions.handle("plugin-a", CommandRequest.class, "calc", null, request -> {
            calls.incrementAndGet();
            return "ok";
        }, RegisterOptions.DEFAULT);

        // When
        extensions.handler(CommandRequest.class, "calc");

        // Then
        assertEquals(0, calls.get());
    }

    @Test
    void invoke_should_call_only_the_given_handler_in_current_thread() {
        // Given
        ExtensionHandler<CommandRequest, Object> given = request -> "given";
        extensions.handle("a", CommandRequest.class, "calc", null, given, RegisterOptions.DEFAULT);
        AtomicInteger otherCalls = new AtomicInteger();
        extensions.handle("b", CommandRequest.class, "other", null, request -> {
            otherCalls.incrementAndGet();
            return "other";
        }, RegisterOptions.DEFAULT);
        CommandRequest request = new CommandRequest("calc", Object.class, null);

        // When
        String thread = Thread.currentThread().getName();
        Object result = extensions.invoke(given, request);

        // Then：内联执行、只碰传入的处理器
        assertEquals("given", result);
        assertEquals(0, otherCalls.get());
        assertEquals(thread, Thread.currentThread().getName());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void invoke_should_validate_result_type() {
        // Given：请求声明的结果是 ToolCallResult，处理器却返回字符串（用原始类型绕过编译期检查）
        ExtensionHandler broken = new ExtensionHandler() {
            @Override
            public Object handle(ExtensionRequest request) {
                return "oops";
            }
        };
        ToolCallRequest request = new ToolCallRequest("calculator", Collections.<String, Object>emptyMap());

        // When
        ExtensionException exception = assertThrows(ExtensionException.class,
                () -> extensions.invoke(broken, request));

        // Then
        assertEquals(ExtensionException.Code.RESULT_TYPE_MISMATCH, exception.getCode());
    }

    @Test
    void invoke_should_allow_null_result() {
        // Given
        ExtensionHandler<CommandRequest, Object> handler = request -> null;
        CommandRequest request = new CommandRequest("calc", Object.class, null);

        // Then
        assertNull(extensions.invoke(handler, request));
    }

    @Test
    void invoke_should_rethrow_runtime_exception_from_handler() {
        // Given
        ExtensionHandler<CommandRequest, Object> handler = request -> {
            throw new IllegalStateException("boom");
        };

        // When / Then
        assertThrows(IllegalStateException.class,
                () -> extensions.invoke(handler, new CommandRequest("calc", Object.class, null)));
    }

    @Test
    void invoke_should_wrap_checked_exception_from_handler() {
        // Given
        ExtensionHandler<CommandRequest, Object> handler = request -> {
            throw new Exception("checked");
        };

        // When
        JellyfishException exception = assertThrows(JellyfishException.class,
                () -> extensions.invoke(handler, new CommandRequest("calc", Object.class, null)));

        // Then
        assertEquals("checked", exception.getCause().getMessage());
    }

    @Test
    void caller_loop_should_support_short_circuit() {
        // Given：第一个处理器返回中止信号，第二个不应被调用
        AtomicInteger secondCalls = new AtomicInteger();
        ExtensionHandler<ContributionRequest, String> stopper = request -> "stop";
        ExtensionHandler<ContributionRequest, String> tail = request -> {
            secondCalls.incrementAndGet();
            return "tail";
        };
        extensions.contribute("stopper", ContributionRequest.class, null, stopper, RegisterOptions.order(-1));
        extensions.contribute("tail", ContributionRequest.class, null, tail, RegisterOptions.DEFAULT);
        ContributionRequest request = new ContributionRequest();

        // When：调用点自行决定何时停止
        String merged = "";
        for (ExtensionHandler<ContributionRequest, String> handler
                : extensions.handlers(ContributionRequest.class, null)) {
            merged = extensions.invoke(handler, request);
            if ("stop".equals(merged)) {
                break;
            }
        }

        // Then
        assertEquals("stop", merged);
        assertEquals(0, secondCalls.get());
    }

    @Test
    void caller_loop_should_support_chaining() {
        // Given：两个处理器依次在前一个结果上追加
        ExtensionHandler<ChainedRequest, String> first = request -> request.getValue() + "-a";
        ExtensionHandler<ChainedRequest, String> second = request -> request.getValue() + "-b";
        extensions.contribute("a", ChainedRequest.class, null, first, RegisterOptions.order(1));
        extensions.contribute("b", ChainedRequest.class, null, second, RegisterOptions.order(2));

        // When
        String value = "";
        for (ExtensionHandler<ChainedRequest, String> handler
                : extensions.handlers(ChainedRequest.class, null)) {
            value = extensions.invoke(handler, new ChainedRequest(value));
        }

        // Then
        assertEquals("-a-b", value);
    }

    @Test
    void descriptors_should_return_all_descriptors_of_type() {
        // Given
        ToolDescriptor calculator = new ToolDescriptor("calculator", "算一下");
        extensions.handle("plugin-a", ToolCallRequest.class, "calculator", calculator, request -> null,
                RegisterOptions.DEFAULT);
        extensions.handle("plugin-b", ToolCallRequest.class, "clock", null, request -> null, RegisterOptions.DEFAULT);

        // When
        List<ToolDescriptor> descriptors = extensions.descriptors(ToolCallRequest.class, ToolDescriptor.class);

        // Then
        assertEquals(Collections.singletonList(calculator), descriptors);
    }

    @Test
    void descriptors_should_throw_when_type_mismatches() {
        // Given
        extensions.handle("plugin-a", ToolCallRequest.class, "calculator", "not-a-descriptor", request -> null,
                RegisterOptions.DEFAULT);

        // When
        ExtensionException exception = assertThrows(ExtensionException.class,
                () -> extensions.descriptors(ToolCallRequest.class, ToolDescriptor.class));

        // Then
        assertEquals(ExtensionException.Code.DESCRIPTOR_TYPE_MISMATCH, exception.getCode());
    }

    @Test
    void unregisterAll_should_drop_registrations_of_owner_only() {
        // Given
        extensions.handle("plugin-a", CommandRequest.class, "calc", null, request -> "one", RegisterOptions.DEFAULT);
        extensions.contribute("kernel", ContributionRequest.class, null, request -> "k", RegisterOptions.DEFAULT);

        // When
        int removed = extensions.unregisterAll("plugin-a");

        // Then
        assertEquals(1, removed);
        assertTrue(extensions.handlers(CommandRequest.class, "calc").isEmpty());
        assertEquals(1, extensions.handlers(ContributionRequest.class, null).size());
    }

    @Test
    void snapshot_should_render_registrations() {
        // Given
        extensions.handle("plugin-a", CommandRequest.class, "calc", null, request -> "one",
                RegisterOptions.order(2));

        // Then
        String rendered = extensions.snapshot().render();
        assertTrue(rendered.contains("CommandRequest"));
        assertTrue(rendered.contains("calc"));
        assertTrue(rendered.contains("order=2"));
        assertTrue(rendered.contains("<- plugin-a"));
    }

    @Test
    void construction_should_reject_null_registry() {
        // When / Then
        assertThrows(NullPointerException.class, () -> new ExtensionRegistry(null));
    }

    @Test
    void handle_should_reject_null_options() {
        // When / Then
        assertThrows(NullPointerException.class,
                () -> extensions.handle("owner", CommandRequest.class, "calc", null, request -> "ok", null));
        assertThrows(NullPointerException.class,
                () -> extensions.contribute("owner", CommandRequest.class, null, request -> "ok", null));
    }

    /**
     * 测试用类型级请求。
     *
     * @author zcd
     */
    private static final class ContributionRequest extends ExtensionRequest<String> {

        /**
         * 构造测试请求。
         */
        private ContributionRequest() {
            super(String.class, null);
        }

        @Override
        public String getRouteKey() {
            return null;
        }
    }

    /**
     * 测试用可携带中间值的请求，用于验证链式编排。
     *
     * @author zcd
     */
    private static final class ChainedRequest extends ExtensionRequest<String> {

        /** 上一位处理器的输出。 */
        private final String value;

        /**
         * 构造链式请求。
         *
         * @param value 上一位处理器的输出
         */
        private ChainedRequest(String value) {
            super(String.class, null);
            this.value = value;
        }

        /**
         * 获取上一位处理器的输出。
         *
         * @return 中间值
         */
        private String getValue() {
            return value;
        }

        @Override
        public String getRouteKey() {
            return null;
        }
    }
}
