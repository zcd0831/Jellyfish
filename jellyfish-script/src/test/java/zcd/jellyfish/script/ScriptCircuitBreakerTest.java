package zcd.jellyfish.script;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 熔断状态机的单元测试：把全部转移逐条钉住。
 * <p>
 * 时间源是注入的假时钟，因此「冷却 60 秒后自动半开」与「探测失败 3 轮转永久」这两条
 * 本来要跑几分钟的转移，在这里是确定性的、毫秒级的。<b>用真实时钟验它们只能靠 sleep，
 * 而 sleep 既慢又不稳</b>——那种测试的失败信号会变成「偶尔红一次」，然后被忽略。
 *
 * @author zcd
 */
@DisplayName("脚本熔断状态机")
class ScriptCircuitBreakerTest {

    /** 假时钟，单位为毫秒。 */
    private final AtomicLong now = new AtomicLong(1_000_000L);

    /**
     * 造一个被测熔断器。
     *
     * @param settings 熔断参数
     * @param listener 观察者，可为 {@code null}
     * @return 熔断器
     */
    private ScriptCircuitBreaker breaker(CircuitBreakerSettings settings, ScriptCircuitListener listener) {
        return new ScriptCircuitBreaker("jira", settings, listener, now::get);
    }

    @Test
    @DisplayName("阈值之前应保持正常，达到阈值才打开")
    void recordFailure_should_openOnlyAtThreshold() {
        ScriptCircuitBreaker breaker = breaker(CircuitBreakerSettings.builder().failuresToOpen(2).build(), null);

        assertTrue(breaker.admit());
        breaker.recordFailure();
        assertEquals(ScriptCircuitBreaker.State.CLOSED, breaker.state());
        assertEquals(1, breaker.consecutiveFailures());

        assertTrue(breaker.admit());
        breaker.recordFailure();
        assertEquals(ScriptCircuitBreaker.State.OPEN, breaker.state());
        assertEquals(2, breaker.consecutiveFailures());
    }

    @Test
    @DisplayName("打开期间应拒绝派发，冷却到期后自动放行且转为探测中")
    void admit_should_refuseUntilCooldownElapsed_thenHalfOpen() {
        ScriptCircuitBreaker breaker = breaker(CircuitBreakerSettings.builder()
                .failuresToOpen(1).cooldownSeconds(60).build(), null);
        breaker.recordFailure();
        assertEquals(ScriptCircuitBreaker.State.OPEN, breaker.state());

        assertFalse(breaker.admit());
        assertEquals(60000L, breaker.retryAfterMillis());

        now.addAndGet(59_999L);
        assertFalse(breaker.admit(), "冷却差 1 毫秒也应继续拒绝");

        now.addAndGet(1L);
        assertTrue(breaker.admit());
        assertEquals(ScriptCircuitBreaker.State.HALF_OPEN, breaker.state());
    }

    @Test
    @DisplayName("冷却配 0 时下一次调用立即可探测")
    void admit_should_allowImmediately_whenCooldownIsZero() {
        ScriptCircuitBreaker breaker = breaker(CircuitBreakerSettings.builder()
                .failuresToOpen(1).cooldownSeconds(0).build(), null);
        breaker.recordFailure();

        assertTrue(breaker.admit());
        assertEquals(ScriptCircuitBreaker.State.HALF_OPEN, breaker.state());
    }

    @Test
    @DisplayName("探测成功即恢复，并清零连续失败与探测轮数")
    void recordSuccess_should_closeCircuit_whenHalfOpen() {
        List<String> events = new ArrayList<String>();
        ScriptCircuitBreaker breaker = breaker(CircuitBreakerSettings.builder()
                .failuresToOpen(1).cooldownSeconds(0).build(), listener(events));
        breaker.recordFailure();
        assertTrue(breaker.admit());

        breaker.recordSuccess();

        assertEquals(ScriptCircuitBreaker.State.CLOSED, breaker.state());
        assertEquals(0, breaker.consecutiveFailures());
        assertEquals(0, breaker.openRounds());
        assertEquals(0L, breaker.retryAfterMillis());
        assertEquals(2, events.size(), events.toString());
        assertTrue(events.get(1).startsWith("恢复"), events.toString());
    }

    @Test
    @DisplayName("探测失败应重新打开并重新计时，而不是顺延剩余时间")
    void recordFailure_should_reopenAndRestartCooldown_whenHalfOpen() {
        ScriptCircuitBreaker breaker = breaker(CircuitBreakerSettings.builder()
                .failuresToOpen(1).cooldownSeconds(60).roundsToPermanent(3).build(), null);
        breaker.recordFailure();
        now.addAndGet(60_000L);
        assertTrue(breaker.admit());

        breaker.recordFailure();

        assertEquals(ScriptCircuitBreaker.State.OPEN, breaker.state());
        assertEquals(1, breaker.openRounds());
        assertEquals(60_000L, breaker.retryAfterMillis());
    }

    @Test
    @DisplayName("探测连续失败到上限应转永久，并且再也不放行")
    void recordFailure_should_becomePermanent_atRoundsLimit() {
        List<String> events = new ArrayList<String>();
        ScriptCircuitBreaker breaker = breaker(CircuitBreakerSettings.builder()
                .failuresToOpen(1).cooldownSeconds(0).roundsToPermanent(2).build(), listener(events));

        breaker.recordFailure();
        assertTrue(breaker.admit());
        breaker.recordFailure();
        assertEquals(ScriptCircuitBreaker.State.OPEN, breaker.state());

        assertTrue(breaker.admit());
        breaker.recordFailure();

        assertEquals(ScriptCircuitBreaker.State.PERMANENT, breaker.state());
        now.addAndGet(86_400_000L);
        assertFalse(breaker.admit(), "永久态不该因为时间流逝而放行");
        assertFalse(breaker.admit());
        // 三次打开：正常态打开一次、两轮探测各重新打开一次（最后一轮直接转永久）；
        // 此后无论拒绝多少次都不再通知——重复通知会把「它坏了」淹没在噪声里
        assertEquals(3, events.size(), events.toString());
        assertTrue(events.get(1).contains("第 1 轮探测失败"), events.toString());
        assertTrue(events.get(2).contains("已停止调用"), events.toString());
    }

    @Test
    @DisplayName("配成 0 的阈值应分别表示不熔断、永不转永久")
    void thresholds_should_disableBehavior_whenZero() {
        ScriptCircuitBreaker never = breaker(CircuitBreakerSettings.builder().failuresToOpen(0).build(), null);
        for (int i = 0; i < 10; i++) {
            never.recordFailure();
        }
        assertEquals(ScriptCircuitBreaker.State.CLOSED, never.state());
        assertTrue(never.admit());

        ScriptCircuitBreaker foreverOpen = breaker(CircuitBreakerSettings.builder()
                .failuresToOpen(1).cooldownSeconds(0).roundsToPermanent(0).build(), null);
        foreverOpen.recordFailure();
        for (int i = 0; i < 10; i++) {
            assertTrue(foreverOpen.admit());
            foreverOpen.recordFailure();
            assertEquals(ScriptCircuitBreaker.State.OPEN, foreverOpen.state());
        }
    }

    @Test
    @DisplayName("打开态下的失败不再累加，避免剩余时间不可预测")
    void recordFailure_should_notCount_whenAlreadyOpen() {
        ScriptCircuitBreaker breaker = breaker(CircuitBreakerSettings.builder()
                .failuresToOpen(1).cooldownSeconds(60).build(), null);
        breaker.recordFailure();
        now.addAndGet(10_000L);

        breaker.recordFailure();

        assertEquals(50_000L, breaker.retryAfterMillis());
        assertEquals(1, breaker.consecutiveFailures());
    }

    @Test
    @DisplayName("查看状态不应推进状态：冷却到期仍是 OPEN，只有 admit 才进探测中")
    void state_should_notAdvanceState_whenCooldownElapsed() {
        ScriptCircuitBreaker breaker = breaker(CircuitBreakerSettings.builder()
                .failuresToOpen(1).cooldownSeconds(60).build(), null);
        breaker.recordFailure();

        now.addAndGet(60_000L);

        // 冷却已到期，但还没有任何一次探测被放行，因此事实仍是「打开」
        assertEquals(ScriptCircuitBreaker.State.OPEN, breaker.state());
        assertEquals(0L, breaker.retryAfterMillis());
        assertTrue(breaker.describe().contains("随时可探测"), breaker.describe());

        assertTrue(breaker.admit());
        assertEquals(ScriptCircuitBreaker.State.HALF_OPEN, breaker.state());
    }

    @Test
    @DisplayName("打开只通知一次；恢复也通知一次")
    void listener_should_beNotifiedOncePerTransition() {
        List<String> events = new ArrayList<String>();
        ScriptCircuitBreaker breaker = breaker(CircuitBreakerSettings.builder()
                .failuresToOpen(2).cooldownSeconds(0).build(), listener(events));

        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
        assertTrue(breaker.admit());
        breaker.recordSuccess();

        assertEquals(2, events.size(), events.toString());
        assertTrue(events.get(0).startsWith("打开"), events.toString());
        assertTrue(events.get(1).startsWith("恢复"), events.toString());
    }

    @Test
    @DisplayName("观察者抛异常不应影响熔断判定")
    void listener_should_notBreakBreaker_whenItThrows() {
        ScriptCircuitListener throwing = new ScriptCircuitListener() {
            @Override
            public void onOpened(String scriptId, ScriptCircuitBreaker.State state, String detail) {
                throw new JellyfishException("订阅者炸了");
            }

            @Override
            public void onRecovered(String scriptId, String detail) {
                throw new JellyfishException("订阅者炸了");
            }
        };
        ScriptCircuitBreaker breaker = breaker(CircuitBreakerSettings.builder()
                .failuresToOpen(1).cooldownSeconds(0).build(), throwing);

        breaker.recordFailure();
        assertEquals(ScriptCircuitBreaker.State.OPEN, breaker.state());
        assertTrue(breaker.admit());
        breaker.recordSuccess();
        assertEquals(ScriptCircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    @DisplayName("状态自述应给出可读的中文与剩余秒数")
    void describe_should_renderReadableState() {
        ScriptCircuitBreaker breaker = breaker(CircuitBreakerSettings.builder()
                .failuresToOpen(1).cooldownSeconds(60).build(), null);
        assertEquals("正常", breaker.describe());

        breaker.recordFailure();
        assertTrue(breaker.describe().startsWith("熔断中"), breaker.describe());
        assertTrue(breaker.describe().contains("60 秒后重试"), breaker.describe());

        now.addAndGet(60_000L);
        assertTrue(breaker.describe().contains("随时可探测"), breaker.describe());
        assertTrue(breaker.admit());
        assertTrue(breaker.describe().contains("探测中"), breaker.describe());
    }

    @Test
    @DisplayName("身份与参数非法应在构造期报错")
    void constructor_should_rejectInvalidArguments() {
        assertTrue(assertThrows(JellyfishException.class,
                () -> new ScriptCircuitBreaker("  ", CircuitBreakerSettings.defaults(), null))
                .getMessage().contains("脚本标识"));
        assertTrue(assertThrows(JellyfishException.class,
                () -> new ScriptCircuitBreaker("jira", null, null)).getMessage().contains("熔断参数"));
        assertTrue(assertThrows(JellyfishException.class,
                () -> new ScriptCircuitBreaker("jira", CircuitBreakerSettings.defaults(), null, null))
                .getMessage().contains("时间源"));
        assertEquals("jira", breaker(CircuitBreakerSettings.defaults(), null).scriptId());
    }

    /**
     * 造一个把事件记成字符串的观察者。
     *
     * @param events 事件输出
     * @return 观察者
     */
    private static ScriptCircuitListener listener(List<String> events) {
        return new ScriptCircuitListener() {
            @Override
            public void onOpened(String scriptId, ScriptCircuitBreaker.State state, String detail) {
                events.add("打开: " + scriptId + " " + state + " " + detail);
            }

            @Override
            public void onRecovered(String scriptId, String detail) {
                events.add("恢复: " + scriptId + " " + detail);
            }
        };
    }
}
