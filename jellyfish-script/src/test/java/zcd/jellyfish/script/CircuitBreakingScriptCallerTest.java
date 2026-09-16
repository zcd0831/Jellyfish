package zcd.jellyfish.script;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.script.protocol.ScriptCallException;
import zcd.jellyfish.script.protocol.ScriptConnectionException;
import zcd.jellyfish.script.protocol.ScriptProtocol;
import zcd.jellyfish.script.protocol.ScriptTimeoutException;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 带熔断的调用入口的单元测试。
 * <p>
 * 这里要钉住的两件事都不是「能不能拒绝」，而是<b>拒绝之后还剩下什么</b>：
 * 第一，拒绝必须发生在派发之前（否则「熔断」只是个错误文案）；第二，哪些失败不该记账
 * （连接故障与熔断自己的拒绝）——记错了账，熔断要么永不恢复，要么被自己的拒绝无限延长。
 *
 * @author zcd
 */
@DisplayName("带熔断的脚本调用入口")
class CircuitBreakingScriptCallerTest {

    /** 两个不同的脚本，用于验证故障不连坐。 */
    private static final ScriptPlugin JIRA = script("jira");

    /** 另一个脚本。 */
    private static final ScriptPlugin GIT = script("git");

    @Test
    @DisplayName("正常调用应原样返回结果并把计数清零")
    void call_should_returnResultAndStayClosed() {
        CircuitBreakingScriptCaller caller = caller(fixed("{\"output\":\"ok\"}"), 2, 60, 3, null);

        JsonNode result = caller.call(JIRA, "tool", null);

        assertEquals("ok", result.path("output").asText());
        assertEquals(ScriptCircuitBreaker.State.CLOSED, caller.stateOf("jira"));
    }

    @Test
    @DisplayName("达到阈值后应拒绝派发，且不再触达被装饰者")
    void call_should_refuseWithoutDispatching_whenOpen() {
        AtomicInteger dispatches = new AtomicInteger();
        CircuitBreakingScriptCaller caller = caller((plugin, type, request) -> {
            dispatches.incrementAndGet();
            throw new JellyfishException("脚本炸了");
        }, 2, 60, 3, null);

        assertThrows(JellyfishException.class, () -> caller.call(JIRA, "tool", null));
        assertThrows(JellyfishException.class, () -> caller.call(JIRA, "tool", null));
        assertEquals(2, dispatches.get());

        // 第三次开始：拒绝发生在派发之前，被装饰者的调用次数不再增长
        ScriptCallException refusal = assertThrows(ScriptCallException.class,
                () -> caller.call(JIRA, "tool", null));

        assertEquals(2, dispatches.get(), "熔断期间不该再派发");
        assertTrue(refusal.isCircuitOpen(), "拒绝必须用 -32001，上层据此区分「拒绝」与「脚本失败」");
        assertEquals(ScriptProtocol.CODE_CIRCUIT_OPEN, refusal.code());
        assertTrue(refusal.getMessage().contains("将在 60 秒后自动重试"), refusal.getMessage());
    }

    @Test
    @DisplayName("一个脚本熔断不应影响同语言的另一个脚本")
    void call_should_isolateScripts_fromEachOther() {
        CircuitBreakingScriptCaller caller = caller((plugin, type, request) -> {
            if ("jira".equals(plugin.id())) {
                throw new JellyfishException("jira 炸了");
            }
            return ScriptJson.tree("{\"output\":\"ok\"}");
        }, 1, 60, 3, null);

        assertThrows(JellyfishException.class, () -> caller.call(JIRA, "tool", null));
        assertEquals(ScriptCircuitBreaker.State.OPEN, caller.stateOf("jira"));

        JsonNode result = caller.call(GIT, "tool", null);

        assertEquals("ok", result.path("output").asText());
        assertEquals(ScriptCircuitBreaker.State.CLOSED, caller.stateOf("git"));
    }

    @Test
    @DisplayName("连接故障不计入熔断：那是整门语言的问题，不是这个脚本的")
    void call_should_notCountConnectionFailure() {
        CircuitBreakingScriptCaller caller = caller((plugin, type, request) -> {
            throw new ScriptConnectionException("网关没了", null);
        }, 1, 60, 3, null);

        for (int i = 0; i < 5; i++) {
            assertThrows(ScriptConnectionException.class, () -> caller.call(JIRA, "tool", null));
        }

        assertEquals(ScriptCircuitBreaker.State.CLOSED, caller.stateOf("jira"),
                "网关是懒启动的，一次短暂故障不该让所有脚本再等一个冷却");
    }

    @Test
    @DisplayName("熔断自己的拒绝不计入熔断，否则冷却会被自己的拒绝无限延长")
    void call_should_notCountCircuitOpenRefusal() {
        CircuitBreakingScriptCaller caller = caller((plugin, type, request) -> {
            throw new ScriptCallException(ScriptProtocol.CODE_CIRCUIT_OPEN, "上游熔断了");
        }, 3, 60, 3, null);

        for (int i = 0; i < 10; i++) {
            assertThrows(ScriptCallException.class, () -> caller.call(JIRA, "tool", null));
        }

        assertEquals(ScriptCircuitBreaker.State.CLOSED, caller.stateOf("jira"));
    }

    @Test
    @DisplayName("超时计入熔断：它是「脚本没能答复」的典型形态")
    void call_should_countTimeout() {
        CircuitBreakingScriptCaller caller = caller((plugin, type, request) -> {
            throw new ScriptTimeoutException("脚本调用超时", 1000L);
        }, 1, 60, 3, null);

        assertThrows(JellyfishException.class, () -> caller.call(JIRA, "tool", null));

        assertEquals(ScriptCircuitBreaker.State.OPEN, caller.stateOf("jira"));
    }

    @Test
    @DisplayName("成功应清掉连续失败计数，凑不到阈值就不该打开")
    void call_should_resetFailureCount_whenSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        CircuitBreakingScriptCaller caller = caller((plugin, type, request) -> {
            if (attempts.incrementAndGet() % 2 == 1) {
                throw new JellyfishException("奇数次要失败");
            }
            return ScriptJson.tree("{\"output\":\"ok\"}");
        }, 2, 60, 3, null);

        for (int i = 0; i < 6; i++) {
            if (i % 2 == 0) {
                assertThrows(JellyfishException.class, () -> caller.call(JIRA, "tool", null));
            } else {
                caller.call(JIRA, "tool", null);
            }
        }

        assertEquals(ScriptCircuitBreaker.State.CLOSED, caller.stateOf("jira"));
    }

    @Test
    @DisplayName("永久态应给出与 /reload 对应的文案，而不是一个假的剩余秒数")
    void call_should_explainPermanentState() {
        CircuitBreakingScriptCaller caller = caller((plugin, type, request) -> {
            throw new JellyfishException("脚本炸了");
        }, 1, 0, 1, null);

        // 第一次失败打开；冷却为 0，第二次被放行做探测，探测失败即转永久
        assertThrows(JellyfishException.class, () -> caller.call(JIRA, "tool", null));
        assertThrows(JellyfishException.class, () -> caller.call(JIRA, "tool", null));
        ScriptCallException refusal = assertThrows(ScriptCallException.class,
                () -> caller.call(JIRA, "tool", null));

        assertEquals(ScriptCircuitBreaker.State.PERMANENT, caller.stateOf("jira"));
        assertTrue(refusal.getMessage().contains("/reload"), refusal.getMessage());
    }

    @Test
    @DisplayName("状态自述应按脚本名排序，且只列出有记录的脚本")
    void states_should_beSortedAndSkipUntouched() {
        CircuitBreakingScriptCaller caller = caller(fixed("{\"output\":\"ok\"}"), 2, 60, 3, null);
        caller.call(GIT, "tool", null);
        caller.call(JIRA, "tool", null);

        List<String> ids = new ArrayList<String>(caller.states().keySet());

        assertEquals(java.util.Arrays.asList("git", "jira"), ids);
        assertEquals(ScriptCircuitBreaker.State.CLOSED, caller.stateOf("未调用过的脚本"));
        assertTrue(caller.states().get("jira").contains("正常"), caller.states().toString());
    }

    @Test
    @DisplayName("状态迁移应交给观察者，供装配方发成告警事件")
    void call_should_notifyListener_onTransition() {
        List<String> events = new ArrayList<String>();
        CircuitBreakingScriptCaller caller = caller((plugin, type, request) -> {
            throw new JellyfishException("脚本炸了");
        }, 1, 0, 3, new ScriptCircuitListener() {
            @Override
            public void onOpened(String scriptId, ScriptCircuitBreaker.State state, String detail) {
                events.add("+" + scriptId + ":" + state);
            }

            @Override
            public void onRecovered(String scriptId, String detail) {
                events.add("-" + scriptId);
            }
        });

        assertThrows(JellyfishException.class, () -> caller.call(JIRA, "tool", null));

        assertEquals(java.util.Arrays.asList("+jira:OPEN"), events);
    }

    @Test
    @DisplayName("参数非法应在构造期报错")
    void constructor_should_rejectNullArguments() {
        ScriptCaller delegate = fixed("{}");
        assertThrows(JellyfishException.class,
                () -> new CircuitBreakingScriptCaller(null, CircuitBreakerSettings.defaults(), null));
        assertThrows(JellyfishException.class,
                () -> new CircuitBreakingScriptCaller(delegate, null, null));
    }

    /**
     * 造一个被测调用入口。
     *
     * @param delegate          被装饰的调用入口
     * @param failuresToOpen    连续失败阈值
     * @param cooldownSeconds   冷却秒数
     * @param roundsToPermanent 转永久的探测轮数
     * @param listener          状态变化观察者，可为 {@code null}
     * @return 调用入口
     */
    private static CircuitBreakingScriptCaller caller(ScriptCaller delegate, int failuresToOpen,
                                                      int cooldownSeconds, int roundsToPermanent,
                                                      ScriptCircuitListener listener) {
        return new CircuitBreakingScriptCaller(delegate, CircuitBreakerSettings.builder()
                .failuresToOpen(failuresToOpen)
                .cooldownSeconds(cooldownSeconds)
                .roundsToPermanent(roundsToPermanent)
                .build(), listener);
    }

    /**
     * 造一个总是返回同一载荷的调用入口。
     *
     * @param json 结果载荷
     * @return 调用入口
     */
    private static ScriptCaller fixed(String json) {
        return (plugin, typeName, request) -> ScriptJson.tree(json);
    }

    /**
     * 造一个只用于熔断建档的脚本。
     *
     * @param id 脚本标识
     * @return 脚本
     */
    private static ScriptPlugin script(String id) {
        return new ScriptPlugin(id, Paths.get("/tmp/scripts").resolve(id),
                ScriptManifest.parse("{\"entry\":\"main.py\"}", id,
                        zcd.jellyfish.script.codec.ExtensionCodecs.DEFAULTS));
    }
}
