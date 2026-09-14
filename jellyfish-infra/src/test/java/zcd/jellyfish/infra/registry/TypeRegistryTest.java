package zcd.jellyfish.infra.registry;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.ExtensionException;
import zcd.jellyfish.api.extension.ExtensionRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolDescriptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TypeRegistry} 的单元测试：验证两种登记入口、匹配与排序、按来源回收、描述符取回与并发安全。
 *
 * @author zcd
 */
class TypeRegistryTest {

    /** 被测注册表。 */
    private final TypeRegistry registry = new TypeRegistry();

    @Test
    void registerUnique_should_store_registration_when_key_is_free() {
        // When
        HandlerRegistration registration = registry.registerUnique("builtin", CommandRequest.class, "calc",
                "handler", null, 0, false);

        // Then
        assertEquals("builtin", registration.getOwner());
        assertEquals(CommandRequest.class, registration.getType());
        assertEquals("calc", registration.getRouteKey());
        assertEquals("handler", registration.getHandler());
        assertNull(registration.getDescriptor());
        assertNull(registration.getOverriddenOwner());
        assertEquals(1, registry.resolve(CommandRequest.class, "calc").size());
    }

    @Test
    void registerUnique_should_throw_duplicate_handler_when_key_occupied() {
        // Given
        registry.registerUnique("builtin", CommandRequest.class, "calc", "one", null, 0, false);

        // When
        ExtensionException exception = assertThrows(ExtensionException.class,
                () -> registry.registerUnique("plugin-a", CommandRequest.class, "calc", "two", null, 0, false));

        // Then
        assertEquals(ExtensionException.Code.DUPLICATE_HANDLER, exception.getCode());
        assertTrue(exception.getMessage().contains("builtin"));
    }

    @Test
    void registerUnique_should_replace_and_record_overridden_owner_when_override_declared() {
        // Given
        registry.registerUnique("builtin", CommandRequest.class, "calc", "one", null, 0, false);

        // When
        HandlerRegistration registration = registry.registerUnique("plugin-a", CommandRequest.class, "calc",
                "two", null, 0, true);

        // Then
        assertEquals("builtin", registration.getOverriddenOwner());
        List<HandlerRegistration> resolved = registry.resolve(CommandRequest.class, "calc");
        assertEquals(1, resolved.size());
        assertEquals("two", resolved.get(0).getHandler());
    }

    @Test
    void registerShared_should_allow_multiple_handlers_for_same_key() {
        // When
        registry.registerShared("owner-a", ContributionRequest.class, null, "a", null, 0);
        registry.registerShared("owner-b", ContributionRequest.class, null, "b", null, 0);

        // Then
        assertEquals(2, registry.resolve(ContributionRequest.class, null).size());
    }

    @Test
    void resolve_should_sort_by_order_then_registration_sequence() {
        // Given：故意让后注册的 order 更小
        registry.registerShared("late", ContributionRequest.class, null, "late", null, 10);
        registry.registerShared("early", ContributionRequest.class, null, "early", null, -5);
        registry.registerShared("middle-first", ContributionRequest.class, null, "m1", null, 0);
        registry.registerShared("middle-second", ContributionRequest.class, null, "m2", null, 0);

        // When
        List<HandlerRegistration> resolved = registry.resolve(ContributionRequest.class, null);

        // Then
        assertEquals(Arrays.asList("early", "m1", "m2", "late"), handlersOf(resolved));
    }

    @Test
    void resolve_should_return_unmodifiable_list() {
        // Given
        registry.registerShared("owner", ContributionRequest.class, null, "a", null, 0);

        // When
        List<HandlerRegistration> resolved = registry.resolve(ContributionRequest.class, null);

        // Then
        assertThrows(UnsupportedOperationException.class, resolved::clear);
    }

    @Test
    void resolve_should_match_subtype_registration_when_querying_child_type() {
        // Given：父类型上的类型级注册应对子类型查询生效
        registry.registerShared("kernel", ContributionRequest.class, null, "parent", null, 0);

        // When
        List<HandlerRegistration> resolved = registry.resolve(NarrowContributionRequest.class, null);

        // Then
        assertEquals(1, resolved.size());
    }

    @Test
    void resolve_should_match_type_wide_registration_for_every_route_key() {
        // Given
        registry.registerShared("plugin-a", CommandRequest.class, null, "wide", null, 0);
        registry.registerUnique("builtin", CommandRequest.class, "calc", "calc-only", null, 0, false);

        // Then：带路由键的查询命中类型级 + 精确匹配；其它路由键只命中类型级
        assertEquals(2, registry.resolve(CommandRequest.class, "calc").size());
        assertEquals(1, registry.resolve(CommandRequest.class, "other").size());
    }

    @Test
    void resolve_should_return_empty_when_nothing_registered() {
        // Then
        assertTrue(registry.resolve(ContributionRequest.class, null).isEmpty());
    }

    @Test
    void registrationsOf_should_ignore_route_key() {
        // Given：工具按工具名（路由键）注册
        registry.registerUnique("plugin-a", ToolCallRequest.class, "calculator", "calc", null, 0, false);
        registry.registerUnique("plugin-b", ToolCallRequest.class, "clock", "clock", null, 0, false);

        // Then
        assertEquals(2, registry.registrationsOf(ToolCallRequest.class).size());
    }

    @Test
    void descriptorsOf_should_return_descriptors_of_every_route_key() {
        // Given
        ToolDescriptor calculator = new ToolDescriptor("calculator", "算一下");
        registry.registerUnique("plugin-a", ToolCallRequest.class, "calculator", "calc", calculator, 0, false);
        registry.registerUnique("plugin-b", ToolCallRequest.class, "clock", "clock", null, 0, false);

        // When：无描述符的注册项被跳过
        List<ToolDescriptor> descriptors = registry.descriptorsOf(ToolCallRequest.class, ToolDescriptor.class);

        // Then
        assertEquals(1, descriptors.size());
        assertSame(calculator, descriptors.get(0));
    }

    @Test
    void descriptorsOf_should_throw_when_descriptor_type_mismatches() {
        // Given
        registry.registerUnique("plugin-a", ToolCallRequest.class, "calculator", "calc", "not-a-descriptor",
                0, false);

        // When
        ExtensionException exception = assertThrows(ExtensionException.class,
                () -> registry.descriptorsOf(ToolCallRequest.class, ToolDescriptor.class));

        // Then
        assertEquals(ExtensionException.Code.DESCRIPTOR_TYPE_MISMATCH, exception.getCode());
    }

    @Test
    void descriptorsOf_should_return_empty_list_when_no_descriptor() {
        // Given
        registry.registerUnique("builtin", ToolCallRequest.class, "calculator", "calc", null, 0, false);

        // Then
        assertTrue(registry.descriptorsOf(ToolCallRequest.class, ToolDescriptor.class).isEmpty());
    }

    @Test
    void remove_should_drop_only_that_registration() {
        // Given
        HandlerRegistration first = registry.registerShared("owner", ContributionRequest.class, null, "a", null, 0);
        registry.registerShared("owner", ContributionRequest.class, null, "b", null, 0);

        // When
        boolean removed = registry.remove(first);

        // Then
        assertTrue(removed);
        assertEquals(Collections.singletonList("b"), handlersOf(registry.resolve(ContributionRequest.class, null)));
        assertFalse(registry.remove(first));
        assertFalse(registry.remove(null));
    }

    @Test
    void removeAll_should_drop_registrations_of_owner_only() {
        // Given
        registry.registerUnique("plugin-a", CommandRequest.class, "calc", "one", null, 0, false);
        registry.registerUnique("plugin-b", CommandRequest.class, "other", "two", null, 0, false);

        // When
        int removed = registry.removeAll("plugin-a");

        // Then
        assertEquals(1, removed);
        assertTrue(registry.resolve(CommandRequest.class, "calc").isEmpty());
        assertEquals(1, registry.resolve(CommandRequest.class, "other").size());
        assertEquals(0, registry.removeAll("unknown"));
    }

    @Test
    void clear_should_drop_everything() {
        // Given
        registry.registerShared("owner", ContributionRequest.class, null, "a", null, 0);

        // When
        registry.clear();

        // Then
        assertTrue(registry.isEmpty());
        assertTrue(registry.resolve(ContributionRequest.class, null).isEmpty());
    }

    @Test
    void register_should_reject_blank_owner_and_null_arguments() {
        // When / Then
        assertThrows(JellyfishException.class,
                () -> registry.registerShared("  ", ContributionRequest.class, null, "a", null, 0));
        assertThrows(JellyfishException.class,
                () -> registry.registerShared(null, ContributionRequest.class, null, "a", null, 0));
        assertThrows(NullPointerException.class,
                () -> registry.registerShared("owner", null, null, "a", null, 0));
        assertThrows(NullPointerException.class,
                () -> registry.registerShared("owner", ContributionRequest.class, null, null, null, 0));
    }

    @Test
    void resolve_should_see_registration_made_after_previous_resolve() {
        // Given：先查一次填充缓存，再注册
        assertTrue(registry.resolve(ContributionRequest.class, null).isEmpty());

        // When
        registry.registerShared("owner", ContributionRequest.class, null, "a", null, 0);

        // Then：写操作必须让缓存失效
        assertEquals(1, registry.resolve(ContributionRequest.class, null).size());
    }

    @Test
    void registrations_should_be_ordered_by_sequence() {
        // Given
        registry.registerShared("a", ContributionRequest.class, null, "a", null, 5);
        registry.registerShared("b", ContributionRequest.class, null, "b", null, -5);

        // When
        List<HandlerRegistration> all = registry.registrations();

        // Then
        assertEquals(Arrays.asList("a", "b"), handlersOf(all));
        assertTrue(registry.snapshot().render().contains("registrations:"));
    }

    @Test
    void concurrent_registration_should_not_lose_entries() throws InterruptedException {
        // Given
        int threads = 8;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        // When
        for (int i = 0; i < threads; i++) {
            final int index = i;
            pool.execute(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        registry.registerShared("plugin-" + index, ContributionRequest.class, null,
                                "h-" + index + "-" + j, null, j);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();

        // Then
        assertEquals(threads * perThread, registry.resolve(ContributionRequest.class, null).size());
    }

    /**
     * 取出注册项里的处理器对象。
     *
     * @param registrations 注册项列表
     * @return 处理器对象列表
     */
    private static List<Object> handlersOf(List<HandlerRegistration> registrations) {
        List<Object> handlers = new ArrayList<>();
        for (HandlerRegistration registration : registrations) {
            handlers.add(registration.getHandler());
        }
        return handlers;
    }

    /**
     * 测试用类型级请求。
     *
     * @author zcd
     */
    private static class ContributionRequest extends ExtensionRequest<String> {

        /**
         * 构造测试请求。
         */
        ContributionRequest() {
            super(String.class, null);
        }

        @Override
        public String getRouteKey() {
            return null;
        }
    }

    /**
     * 测试用子类型请求，用于验证宽窄匹配。
     *
     * @author zcd
     */
    private static final class NarrowContributionRequest extends ContributionRequest {
    }
}
