package zcd.jellyfish.script;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.script.protocol.ScriptCallException;
import zcd.jellyfish.script.protocol.ScriptConnectionException;
import zcd.jellyfish.script.protocol.ScriptProtocol;
import zcd.jellyfish.script.protocol.ScriptTimeoutException;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脚本网关状态机的单元测试。
 * <p>
 * 全部用内存假进程，因此这里的每个场景都是<b>确定性</b>的：初始化下发什么、进程退出后下一次调用
 * 会不会重起、关闭之后还能不能调用——这些判断不依赖解释器，也不依赖真实的时序。
 * 真实管道能否承载逐行 JSON 由 {@link CommonsExecScriptProcessTest} 单独覆盖。
 *
 * @author zcd
 */
@DisplayName("脚本网关")
class ScriptGatewayTest {

    /** 被测脚本。 */
    private ScriptPlugin plugin;

    /** 假进程工厂创建出来的全部进程。 */
    private List<FakeProcess> processes;

    /** 假进程工厂被调用的次数。 */
    private AtomicInteger startCount;

    /** 当前应答策略。 */
    private Function<ScriptProtocol.Message, String> responder;

    /** 被测网关。 */
    private ScriptGateway gateway;

    /**
     * 装配每个用例都需要的脚本与网关。
     */
    @BeforeEach
    void setUp() {
        plugin = new ScriptPlugin("jira", Paths.get("/tmp/jira"),
                ScriptManifest.parse("{\"entry\":\"main.py\",\"tools\":[{\"name\":\"jira_issue\"}],"
                        + "\"commands\":[{\"name\":\"jira\",\"descriptor\":{\"summary\":\"操作 Jira\"}}]}",
                        "jira", zcd.jellyfish.script.codec.ExtensionCodecs.DEFAULTS));
        processes = new ArrayList<FakeProcess>();
        startCount = new AtomicInteger();
        responder = message -> {
            if (ScriptProtocol.METHOD_INITIALIZE.equals(message.method())) {
                return ScriptProtocol.response(message.id().longValue(),
                        ScriptJson.treeOf(initializePayload(true)));
            }
            if (ScriptProtocol.METHOD_INVOKE.equals(message.method())) {
                return ScriptProtocol.response(message.id().longValue(),
                        ScriptJson.tree("{\"output\":\"ok\"}"));
            }
            return ScriptProtocol.response(message.id().longValue(), ScriptJson.tree("{}"));
        };
        gateway = buildGateway(GatewaySettings.defaults());
    }

    @Test
    @DisplayName("第一次调用应起进程、先初始化再转发")
    void call_should_initializeThenInvoke_when_firstCall() {
        JsonNode result = gateway.call(plugin, "tool", ScriptJson.tree("{\"arguments\":{}}"));

        assertEquals("ok", result.get("output").asText());
        assertEquals(1, startCount.get());
        FakeProcess process = processes.get(0);
        assertEquals(ScriptProtocol.METHOD_INITIALIZE, process.sent.get(0).method());
        assertEquals(ScriptProtocol.METHOD_INVOKE, process.sent.get(1).method());
        assertEquals("jira", process.sent.get(1).paramText(ScriptProtocol.PARAM_SCRIPT));
        assertEquals("tool", process.sent.get(1).paramText(ScriptProtocol.PARAM_TYPE));
        assertTrue(gateway.isRunning());
    }

    @Test
    @DisplayName("后续调用应复用同一个进程，不重复初始化")
    void call_should_reuseProcess_when_calledTwice() {
        gateway.call(plugin, "tool", null);
        gateway.call(plugin, "tool", null);

        assertEquals(1, startCount.get());
        assertEquals(1, countOf(ScriptProtocol.METHOD_INITIALIZE, processes.get(0)));
    }

    @Test
    @DisplayName("初始化应下发脚本清单摘要与设置")
    void initialize_should_carryScriptDigestAndSettings() {
        gateway.call(plugin, "tool", null);

        JsonNode params = processes.get(0).sent.get(0).params();
        JsonNode script = params.get(ScriptProtocol.PARAM_SCRIPTS).get(0);
        assertEquals("jira", script.get(ScriptProtocol.PARAM_ID).asText());
        assertTrue(script.get(ScriptProtocol.PARAM_ENTRY).asText().endsWith("main.py"));
        assertEquals("jira_issue", script.get(ScriptProtocol.PARAM_MANIFEST).get("tools").get(0).asText());
        assertEquals("jira", script.get(ScriptProtocol.PARAM_MANIFEST).get("commands").get(0).asText());
        // 只下发名字清单而非整份 manifest：网关要比较的是名字集合，
        // 带上整份会让它不得不跟随清单 schema 的每次演进
        assertEquals(30, params.get(ScriptProtocol.PARAM_SETTINGS).get("invokeTimeoutSeconds").asInt());
    }

    @Test
    @DisplayName("网关回报错误时应原样带出错误码")
    void call_should_propagateErrorCode_when_gatewayReportsFailure() {
        responder = message -> ScriptProtocol.METHOD_INVOKE.equals(message.method())
                ? ScriptProtocol.errorResponse(message.id().longValue(),
                        ScriptProtocol.CODE_SCRIPT_FAILURE, "脚本炸了")
                : initializeResponse(message, true);

        ScriptCallException failure = assertThrows(ScriptCallException.class,
                () -> gateway.call(plugin, "tool", null));

        assertTrue(failure.isScriptFailure());
        assertTrue(failure.getMessage().contains("脚本炸了"), failure.getMessage());
    }

    @Test
    @DisplayName("超时应抛出可识别的超时异常，供上层杀 worker")
    void call_should_throwTimeout_when_gatewayDoesNotAnswer() {
        responder = message -> ScriptProtocol.METHOD_INVOKE.equals(message.method())
                ? null
                : initializeResponse(message, true);
        gateway = buildGateway(GatewaySettings.builder().invokeTimeoutSeconds(1).build());

        assertThrows(ScriptTimeoutException.class, () -> gateway.call(plugin, "tool", null));
    }

    @Test
    @DisplayName("单脚本初始化失败不应让整门语言不可用")
    void call_should_stillWork_when_someScriptFailsToInitialize() {
        responder = message -> ScriptProtocol.METHOD_INITIALIZE.equals(message.method())
                ? ScriptProtocol.response(message.id().longValue(), ScriptJson.treeOf(initializePayload(true, false)))
                : ScriptProtocol.response(message.id().longValue(), ScriptJson.tree("{\"output\":\"ok\"}"));

        assertEquals("ok", gateway.call(plugin, "tool", null).get("output").asText());
    }

    @Test
    @DisplayName("全部脚本初始化失败应立刻报错，而不是等到每次调用超时")
    void call_should_failFast_when_everyScriptFailsToInitialize() {
        responder = message -> ScriptProtocol.METHOD_INITIALIZE.equals(message.method())
                ? ScriptProtocol.response(message.id().longValue(), ScriptJson.treeOf(initializePayload(false)))
                : null;

        JellyfishException failure = assertThrows(JellyfishException.class,
                () -> gateway.call(plugin, "tool", null));

        assertTrue(failure.getMessage().contains("全部脚本初始化失败"), failure.getMessage());
        // 失败的一代必须被丢弃：否则下一次调用会拿到一个「初始化失败但看起来活着」的进程
        assertFalse(gateway.isRunning());
    }

    @Test
    @DisplayName("进程退出后下一次调用应重新起进程，而不是永久失效")
    void call_should_restartProcess_when_previousProcessExited() {
        gateway.call(plugin, "tool", null);
        processes.get(0).exit(9);

        // 重新走懒启动：说明「注册与进程解耦」在退出路径上同样成立
        assertEquals("ok", gateway.call(plugin, "tool", null).get("output").asText());
        assertEquals(2, startCount.get());
    }

    @Test
    @DisplayName("进程退出应立刻唤醒在途调用，而不是让调用等到超时")
    void call_should_wakeInFlightCaller_when_processExits() throws Exception {
        responder = message -> ScriptProtocol.METHOD_INITIALIZE.equals(message.method())
                ? initializeResponse(message, true)
                : null;
        gateway = buildGateway(GatewaySettings.builder().invokeTimeoutSeconds(60).build());
        java.util.concurrent.atomic.AtomicReference<Exception> failure =
                new java.util.concurrent.atomic.AtomicReference<Exception>();
        Thread caller = new Thread(() -> {
            try {
                gateway.call(plugin, "tool", null);
            } catch (Exception e) {
                failure.set(e);
            }
        });
        caller.setDaemon(true);
        caller.start();
        awaitMethod(ScriptProtocol.METHOD_INVOKE);

        long started = System.currentTimeMillis();
        processes.get(0).exit(9);
        caller.join(3000);

        assertTrue(failure.get() instanceof ScriptConnectionException, String.valueOf(failure.get()));
        assertTrue(System.currentTimeMillis() - started < 3000);
    }

    @Test
    @DisplayName("调用超时应主动请网关隔离该脚本，并把「已隔离」写进错误")
    void call_should_isolateWorker_when_invokeTimesOut() {
        // invoke 不回应答；kill_worker 应答「确实杀了」
        responder = message -> {
            if (ScriptProtocol.METHOD_INITIALIZE.equals(message.method())) {
                return initializeResponse(message, true);
            }
            if (ScriptProtocol.METHOD_KILL_WORKER.equals(message.method())) {
                return ScriptProtocol.response(message.id().longValue(),
                        ScriptJson.tree("{\"killed\":true}"));
            }
            return null;
        };
        gateway = buildGateway(GatewaySettings.builder().invokeTimeoutSeconds(1).build());

        ScriptTimeoutException failure = assertThrows(ScriptTimeoutException.class,
                () -> gateway.call(plugin, "tool", null));

        // 「谁来杀」这件事不能只写在文档里：宿主要真的发出指令，否则脚本卡住时
        // 连接看起来完全正常，而 worker 会带着它挂死的那个线程一直占着
        assertTrue(failure.getMessage().contains("已隔离该脚本的 worker"), failure.getMessage());
        assertEquals(1000L, failure.waitedMillis());
        assertEquals(1, countOf(ScriptProtocol.METHOD_KILL_WORKER, processes.get(0)));
    }

    @Test
    @DisplayName("隔离请求没人应答时应如实说没送到，而不是假装隔离成功")
    void call_should_reportUnsentIsolation_when_killRequestIsUnanswered() {
        responder = message -> ScriptProtocol.METHOD_INITIALIZE.equals(message.method())
                ? initializeResponse(message, true)
                : null;
        gateway = buildGateway(GatewaySettings.builder().invokeTimeoutSeconds(1).build());

        ScriptTimeoutException failure = assertThrows(ScriptTimeoutException.class,
                () -> gateway.call(plugin, "tool", null));

        assertTrue(failure.getMessage().contains("隔离请求未能送达"), failure.getMessage());
    }

    @Test
    @DisplayName("隔离请求应把网关回报的结果原样带回来，且未启动时直接返回 false")
    void killWorker_should_reportGatewayAnswer() {
        assertFalse(gateway.killWorker("jira", "测试"), "还没启动过就不该声称杀了什么");

        responder = message -> {
            if (ScriptProtocol.METHOD_INITIALIZE.equals(message.method())) {
                return initializeResponse(message, true);
            }
            if (ScriptProtocol.METHOD_KILL_WORKER.equals(message.method())) {
                return ScriptProtocol.response(message.id().longValue(),
                        ScriptJson.tree("{\"killed\":false}"));
            }
            return ScriptProtocol.response(message.id().longValue(), ScriptJson.tree("{\"output\":\"ok\"}"));
        };
        gateway.call(plugin, "tool", null);

        assertFalse(gateway.killWorker("jira", "测试"), "网关说没有 worker 可杀时不该报 true");
    }

    @Test
    @DisplayName("worker_state 通知应被应答并记录，不影响后续调用")
    void onIncoming_should_respondToWorkerState() {
        gateway.call(plugin, "tool", null);
        FakeProcess process = processes.get(0);

        process.emit("{\"jsonrpc\":\"2.0\",\"method\":\"worker_state\",\"params\":"
                + "{\"script\":\"jira\",\"state\":\"exited\",\"alive\":false,\"started\":true}}");
        process.emit("{\"jsonrpc\":\"2.0\",\"method\":\"worker_state\",\"params\":{\"script\":\"jira\"}}");

        assertEquals("ok", gateway.call(plugin, "tool", null).get("output").asText());
    }

    @Test
    @DisplayName("emit_event 应被明确拒绝，而不是假装受理")
    void onIncoming_should_rejectEmitEvent_when_eventBridgeIsNotConnected() {
        gateway.call(plugin, "tool", null);
        FakeProcess process = processes.get(0);

        process.emit("{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"emit_event\",\"params\":"
                + "{\"script\":\"jira\",\"event\":\"SessionCreatedEvent\"}}");

        ScriptProtocol.Message response = process.sent.get(process.sent.size() - 1);
        assertNotNull(response);
        assertEquals(Long.valueOf(99), response.id());
        assertFalse(response.result().get(ScriptProtocol.PARAM_ACCEPTED).asBoolean(true));
    }

    @Test
    @DisplayName("未知上行方法应回方法不存在，而不是静默丢弃")
    void onIncoming_should_answerMethodNotFound_forUnknownMethod() {
        gateway.call(plugin, "tool", null);
        FakeProcess process = processes.get(0);

        process.emit("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"mystery\",\"params\":{}}");

        ScriptProtocol.Message response = process.sent.get(process.sent.size() - 1);
        assertEquals(Long.valueOf(7), response.id());
        assertEquals(ScriptProtocol.CODE_METHOD_NOT_FOUND, response.errorCode());
    }

    @Test
    @DisplayName("关闭应发 shutdown 并停止进程，之后调用立刻失败")
    void close_should_shutdownProcess_andRejectFurtherCalls() {
        gateway.call(plugin, "tool", null);
        FakeProcess process = processes.get(0);

        gateway.close();

        assertTrue(countOf(ScriptProtocol.METHOD_SHUTDOWN, process) >= 1);
        assertTrue(process.closed);
        assertFalse(gateway.isRunning());
        assertThrows(ScriptConnectionException.class, () -> gateway.call(plugin, "tool", null));
    }

    @Test
    @DisplayName("关闭应幂等，重复调用不重发 shutdown")
    void close_should_beIdempotent() {
        gateway.call(plugin, "tool", null);
        FakeProcess process = processes.get(0);

        gateway.close();
        gateway.close();

        assertEquals(1, countOf(ScriptProtocol.METHOD_SHUTDOWN, process));
    }

    @Test
    @DisplayName("未启动就关闭应是安全空操作")
    void close_should_beSafe_when_neverStarted() {
        gateway.close();

        assertFalse(gateway.isRunning());
    }

    @Test
    @DisplayName("自述文本应区分「未启动」与「运行中」")
    void describe_should_reportRunState() {
        assertTrue(gateway.describe().contains("未启动"), gateway.describe());

        gateway.call(plugin, "tool", null);

        assertTrue(gateway.describe().contains("运行中"), gateway.describe());
    }

    @Test
    @DisplayName("进程启动失败应原样抛出，且不留下半启动状态")
    void call_should_propagateStartFailure_andLeaveNoState() {
        ScriptGateway failing = ScriptGateway.builder(new StubLanguage())
                .scripts(Collections.singletonList(plugin))
                .processFactory((lines, onExit) -> {
                    throw new JellyfishException("脚本进程启动失败: 解释器不存在");
                })
                .build();

        JellyfishException failure = assertThrows(JellyfishException.class,
                () -> failing.call(plugin, "tool", null));

        assertTrue(failure.getMessage().contains("解释器不存在"), failure.getMessage());
        assertFalse(failing.isRunning());
    }

    /**
     * 构造被测网关。
     *
     * @param settings 网关设置
     * @return 网关
     */
    private ScriptGateway buildGateway(GatewaySettings settings) {
        return ScriptGateway.builder(new StubLanguage())
                .scripts(Collections.singletonList(plugin))
                .settings(settings)
                .processFactory((lines, onExit) -> {
                    startCount.incrementAndGet();
                    FakeProcess process = new FakeProcess(lines, onExit, responder);
                    processes.add(process);
                    return process;
                })
                .build();
    }

    /**
     * 构造初始化应答帧。
     *
     * @param message 请求消息
     * @param okFlags 每个脚本的成功标记
     * @return 应答帧
     */
    private static String initializeResponse(ScriptProtocol.Message message, boolean... okFlags) {
        return ScriptProtocol.response(message.id().longValue(),
                ScriptJson.treeOf(initializePayload(okFlags)));
    }

    /**
     * 等到某个方法被发出。
     *
     * @param method 方法名
     * @throws InterruptedException 等待被中断时抛出
     */
    private void awaitMethod(String method) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (!processes.isEmpty() && countOf(method, processes.get(0)) > 0) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("没有等到方法调用: " + method);
    }

    /**
     * 构造初始化应答载荷。
     *
     * @param okFlags 每个脚本的成功标记
     * @return 载荷
     */
    private static Map<String, Object> initializePayload(boolean... okFlags) {
        List<Map<String, Object>> scripts = new ArrayList<Map<String, Object>>();
        for (boolean ok : okFlags) {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put(ScriptProtocol.PARAM_SCRIPT, "jira");
            entry.put(ScriptProtocol.PARAM_OK, Boolean.valueOf(ok));
            entry.put(ScriptProtocol.PARAM_ERROR, ok ? null : "清单与实现不一致");
            scripts.add(entry);
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(ScriptProtocol.PARAM_SCRIPTS, scripts);
        return payload;
    }

    /**
     * 数一数某个方法被发出过几次。
     *
     * @param method  方法名
     * @param process 假进程
     * @return 次数
     */
    private static int countOf(String method, FakeProcess process) {
        int count = 0;
        for (ScriptProtocol.Message message : process.sent) {
            if (method.equals(message.method())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 内存假进程：把收到的帧交给应答策略，并把回帧立刻交回网关。
     * <p>
     * 同步回帧是可行的：{@code ScriptRpc} 先登记等待位、再发送，因此应答到达时等待者一定已在表里。
     * 这恰好也说明「配对逻辑不依赖真实时序」。
     */
    private static final class FakeProcess implements ScriptProcess {

        /** 回帧通道。 */
        private final Consumer<String> lines;

        /** 退出回调。 */
        private final Consumer<Integer> onExit;

        /** 应答策略；返回 {@code null} 表示不答（模拟超时）。 */
        private final Function<ScriptProtocol.Message, String> responder;

        /** 收到的全部帧。 */
        private final List<ScriptProtocol.Message> sent = new ArrayList<ScriptProtocol.Message>();

        /** 是否已被关闭。 */
        private volatile boolean closed;

        /** 是否仍在运行。 */
        private volatile boolean alive = true;

        /**
         * 构造假进程。
         *
         * @param lines     回帧通道
         * @param onExit    退出回调
         * @param responder 应答策略
         */
        private FakeProcess(Consumer<String> lines, Consumer<Integer> onExit,
                            Function<ScriptProtocol.Message, String> responder) {
            this.lines = lines;
            this.onExit = onExit;
            this.responder = responder;
        }

        @Override
        public void send(String line) {
            ScriptProtocol.Message message = ScriptProtocol.parse(line);
            sent.add(message);
            String reply = responder.apply(message);
            if (reply != null) {
                lines.accept(reply);
            }
        }

        @Override
        public boolean isAlive() {
            return alive && !closed;
        }

        @Override
        public void close(long graceMillis, long killMillis) {
            closed = true;
            alive = false;
        }

        /**
         * 模拟进程自行退出。
         *
         * @param code 退出码
         */
        private void exit(int code) {
            alive = false;
            onExit.accept(Integer.valueOf(code));
        }

        /**
         * 模拟上行消息。
         *
         * @param line 帧文本
         */
        private void emit(String line) {
            lines.accept(line);
        }
    }

    /**
     * 只提供身份与启动命令的假语言适配。
     */
    private static final class StubLanguage implements ScriptLanguage {

        @Override
        public String id() {
            return "stub";
        }

        @Override
        public String displayName() {
            return "Stub";
        }

        @Override
        public List<String> probeCommand() {
            return Collections.singletonList("true");
        }

        @Override
        public List<String> startCommand(Path gatewayDirectory) {
            return Collections.singletonList("true");
        }

        @Override
        public Map<String, String> environment() {
            return Collections.emptyMap();
        }
    }
}
