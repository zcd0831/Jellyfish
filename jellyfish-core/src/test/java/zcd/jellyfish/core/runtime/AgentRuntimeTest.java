package zcd.jellyfish.core.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.config.SubAgentSettings;
import zcd.jellyfish.infra.session.SessionUsage;
import zcd.jellyfish.infra.support.CancellationTokenSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AgentRuntime} 的单元测试：派生、等待、异常归一、并发许可与墙钟上限。
 * <p>
 * 用真实的 {@link RunScheduler} 与登记表：本类要验证的正是「调度执行 + 句柄取结果」这条链，
 * 把调度器 mock 掉就什么都没测到。
 *
 * @author zcd
 */
class AgentRuntimeTest {

    @Test
    void spawn_should_run_body_and_return_result() {
        // Given
        Fixture fixture = fixture();
        fixture.contexts.open(2, 8);

        // When
        AgentRunHandle handle = fixture.runtime.spawn(request("s-child"), null,
                h -> AgentRunResult.of(AgentRunStatus.DONE, "ok", 2, new SessionUsage(1L, 1L, 2L, 1L), null));

        // Then
        AgentRunResult result = fixture.runtime.await(handle);
        assertEquals(AgentRunStatus.DONE, result.getStatus());
        assertEquals("ok", result.getText());
        assertEquals(2, result.getRounds());
        assertEquals(2L, result.getUsage().getTotalTokens());
    }

    @Test
    void spawn_should_register_run_as_root_when_no_parent_run() {
        // Given
        Fixture fixture = fixture();
        fixture.contexts.open(2, 8);

        // When
        AgentRunHandle handle = fixture.runtime.spawn(request("s-child"), null, h -> AgentRunResult.failed("x"));

        // Then：顶层回合直接发起，自己就是树根、没有父 run
        AgentRunSnapshot snapshot = fixture.runtime.snapshot(handle.getRunId())
                .orElseThrow(AssertionError::new);
        assertEquals(handle.getRunId(), snapshot.getRootRunId());
        assertNull(snapshot.getParentRunId());
        assertEquals("coder", snapshot.getAgentId());
    }

    @Test
    void spawn_should_execute_body_on_agent_run_thread() {
        // Given
        Fixture fixture = fixture();
        fixture.contexts.open(2, 8);
        String[] threadName = new String[1];

        // When
        AgentRunHandle handle = fixture.runtime.spawn(request("s-child"), null, h -> {
            threadName[0] = Thread.currentThread().getName();
            return AgentRunResult.failed("x");
        });
        fixture.runtime.await(handle);

        // Then：run 跑在专用池上，不再是调用线程（这正是并行执行的地基）
        assertNotNull(threadName[0]);
        assertTrue(threadName[0].startsWith("agent-run-"), threadName[0]);
    }

    @Test
    void body_exception_should_become_failed_result() {
        // Given
        Fixture fixture = fixture();
        fixture.contexts.open(2, 8);

        // When
        AgentRunHandle handle = fixture.runtime.spawn(request("s-child"), null, h -> {
            throw new IllegalStateException("boom");
        });

        // Then
        AgentRunResult result = fixture.runtime.await(handle);
        assertEquals(AgentRunStatus.FAILED, result.getStatus());
        assertTrue(result.getError().contains("boom"));
    }

    @Test
    void spawn_should_throw_when_no_active_context() {
        // Given：没有开回合上下文
        Fixture fixture = fixture();

        // When / Then
        assertThrows(JellyfishException.class,
                () -> fixture.runtime.spawn(request("s-child"), null, h -> AgentRunResult.failed("x")));
    }

    @Test
    @Timeout(20)
    void spawn_should_not_deadlock_when_parent_awaits_child() {
        // Given：全局只发 1 个许可——父 run 等孩子时若不让出许可，这里会挂死（由 @Timeout 兜住）
        Fixture fixture = fixture(1, 2, 0L, 0L, 300_000L);
        fixture.contexts.open(2, 8);

        // When：父 run 内部再派生一个孩子并等它
        AgentRunHandle parent = fixture.runtime.spawn(request("s-parent-run"), null, h -> {
            AgentRunHandle child = fixture.runtime.spawn(request("s-child-run"), null,
                    childHandle -> AgentRunResult.of(AgentRunStatus.DONE, "child", 1, null, null));
            AgentRunResult childResult = fixture.runtime.await(child);
            return AgentRunResult.of(AgentRunStatus.DONE, "parent:" + childResult.getText(), 1, null, null);
        });
        AgentRunResult result = fixture.runtime.await(parent);

        // Then：两个 run 都跑完（等待中的父让出了许可）
        assertEquals(AgentRunStatus.DONE, result.getStatus());
        assertEquals("parent:child", result.getText());
    }

    @Test
    @Timeout(20)
    void spawn_should_truncate_when_wall_clock_exceeded() {
        // Given：墙钟上限 50ms
        Fixture fixture = fixture(1, 2, 0L, 0L, 50L);
        fixture.contexts.open(2, 8);

        // When：执行体一直干到被取消
        AgentRunHandle handle = fixture.runtime.spawn(request("s-child"), null, h -> {
            while (!h.isCancelled() && !Thread.currentThread().isInterrupted()) {
                sleepQuietly();
            }
            return AgentRunResult.of(AgentRunStatus.CANCELLED, null, 0, null, null);
        });
        AgentRunResult result = fixture.runtime.await(handle);

        // Then：被看门狗取消的 run 如实改标为「截断」
        assertEquals(AgentRunStatus.TRUNCATED, result.getStatus());
        assertTrue(result.getError().contains("墙钟"));
    }

    @Test
    @Timeout(20)
    void parent_cancellation_should_cancel_child() {
        // Given：父回合的取消令牌随 spawn 传入
        Fixture fixture = fixture(2, 2, 0L, 0L, 300_000L);
        fixture.contexts.open(2, 8);
        CancellationTokenSource parentToken = new CancellationTokenSource();
        AgentRunHandle child = fixture.runtime.spawn(request("s-child"), parentToken, h -> {
            while (!h.isCancelled()) {
                sleepQuietly();
            }
            return AgentRunResult.of(AgentRunStatus.CANCELLED, null, 0, null, null);
        });

        // When
        parentToken.cancel();
        AgentRunResult result = fixture.runtime.await(child);

        // Then：父取消级联到子 run（否则用户按 Esc 后子代理还在后台跑）
        assertEquals(AgentRunStatus.CANCELLED, result.getStatus());
    }

    @Test
    @Timeout(20)
    void failed_run_should_cancel_orphan_descendants() {
        // Given：父 run 派生了一个孩子后不等它就抛错
        Fixture fixture = fixture(2, 2, 0L, 0L, 300_000L);
        fixture.contexts.open(2, 8);
        AgentRunHandle[] grandchild = new AgentRunHandle[1];
        AgentRunHandle parent = fixture.runtime.spawn(request("s-parent-run"), null, h -> {
            grandchild[0] = fixture.runtime.spawn(request("s-grand"), null, gh -> {
                while (!gh.isCancelled()) {
                    sleepQuietly();
                }
                return AgentRunResult.of(AgentRunStatus.CANCELLED, null, 0, null, null);
            });
            throw new IllegalStateException("parent boom");
        });

        // When
        AgentRunResult result = fixture.runtime.await(parent);

        // Then：父 run 失败，孤儿后代被收尾取消（不会变成没人读结果的永久后台 run）
        assertEquals(AgentRunStatus.FAILED, result.getStatus());
        assertNotNull(grandchild[0]);
        assertEquals(AgentRunStatus.CANCELLED, fixture.runtime.await(grandchild[0]).getStatus());
    }

    @Test
    void spawn_should_publish_started_and_finished_events() {
        // Given：先订阅总线
        Fixture fixture = fixture();
        fixture.contexts.open(2, 8);
        List<AgentRunEvent.Kind> kinds = new ArrayList<AgentRunEvent.Kind>();
        fixture.events.subscribe(event -> kinds.add(event.getKind()));

        // When
        AgentRunHandle handle = fixture.runtime.spawn(request("s-child"), null,
                h -> AgentRunResult.of(AgentRunStatus.DONE, "ok", 1, null, null));
        fixture.runtime.await(handle);

        // Then：恰好 STARTED 后 FINISHED，且都指向同一个 run
        assertEquals(Arrays.asList(AgentRunEvent.Kind.STARTED, AgentRunEvent.Kind.FINISHED), kinds);
    }

    @Test
    void remove_should_drop_entry_after_result_taken() {
        // Given
        Fixture fixture = fixture();
        fixture.contexts.open(2, 8);
        AgentRunHandle handle = fixture.runtime.spawn(request("s-child"), null, h -> AgentRunResult.failed("x"));
        fixture.runtime.await(handle);

        // When
        fixture.runtime.remove(handle.getRunId());

        // Then
        assertFalse(fixture.runtime.snapshot(handle.getRunId()).isPresent());
        assertTrue(fixture.runtime.activeRuns().isEmpty());
    }

    /**
     * 睡一小会儿；被中断时保留中断标记后返回。
     */
    private static void sleepQuietly() {
        try {
            Thread.sleep(5L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 用缺省参数组装一套真实的运行时组件。
     *
     * @return 测试夹具
     */
    private static Fixture fixture() {
        return fixture(3, 2, 0L, 0L, 300_000L);
    }

    /**
     * 组装一套真实的运行时组件，并指定 governor 参数。
     *
     * @param maxConcurrentRuns 全局并发许可数
     * @param maxDepth          最大委派层数
     * @param runTokenBudget    单 run token 预算，{@code 0} 表示不限制
     * @param treeTokenBudget   树 token 预算，{@code 0} 表示不限制
     * @param runTimeoutMillis  单 run 墙钟上限（毫秒）
     * @return 测试夹具
     */
    private static Fixture fixture(int maxConcurrentRuns, int maxDepth, long runTokenBudget,
                                   long treeTokenBudget, long runTimeoutMillis) {
        RuntimeConfig config = mock(RuntimeConfig.class);
        when(config.getSubAgentSettings()).thenReturn(new SubAgentSettings(true, maxDepth, 8, 8,
                maxConcurrentRuns, runTimeoutMillis, runTokenBudget, treeTokenBudget));
        RunContextHolder contexts = new RunContextHolder();
        RunRegistry registry = new RunRegistry();
        RunEventBus events = new RunEventBus();
        RunScheduler scheduler = new RunScheduler(contexts, registry, events, config);
        return new Fixture(new AgentRuntime(registry, contexts, scheduler), contexts, events);
    }

    /**
     * 构造一个登记输入。
     *
     * @param sessionId 本 run 的会话标识
     * @return 登记输入
     */
    private static AgentRunRequest request(String sessionId) {
        return new AgentRunRequest("s-parent", "coder", sessionId, "call-1");
    }

    /**
     * 测试夹具：被测门面、它依赖的上下文持有者与事件总线。
     */
    private static final class Fixture {

        /** 被测门面。 */
        private final AgentRuntime runtime;

        /** 上下文持有者。 */
        private final RunContextHolder contexts;

        /** run 事件总线。 */
        private final RunEventBus events;

        /**
         * 构造夹具。
         *
         * @param runtime  被测门面
         * @param contexts 上下文持有者
         * @param events   run 事件总线
         */
        private Fixture(AgentRuntime runtime, RunContextHolder contexts, RunEventBus events) {
            this.runtime = runtime;
            this.contexts = contexts;
            this.events = events;
        }
    }
}
