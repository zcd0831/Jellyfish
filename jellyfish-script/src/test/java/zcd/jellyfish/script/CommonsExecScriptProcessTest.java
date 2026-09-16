package zcd.jellyfish.script;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.script.protocol.ScriptConnectionException;
import zcd.jellyfish.script.protocol.ScriptProtocol;
import zcd.jellyfish.script.protocol.ScriptRpc;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脚本进程与真实管道的集成测试。
 * <p>
 * 子进程是一个用 Java 写的协议回声（{@link EchoGatewayProcess}），因此本用例不依赖 Python：
 * 它验证的是「进程能不能起、环境是不是被完整替换、字节能不能逐行过管道、脏行会不会毁掉会话、
 * 关闭能不能真的把进程收掉」——这些都是内存假进程<b>永远验证不到</b>的部分。
 * <p>
 * 刻意不使用 mock：这里要证明的正是与操作系统交互的那一段代码。
 *
 * @author zcd
 */
@DisplayName("脚本进程与管道")
class CommonsExecScriptProcessTest {

    /** 子进程句柄，用例结束时统一关闭。 */
    private ScriptProcess process;

    /** 协议会话，由 {@link #start(String)} 装配。 */
    private ScriptRpc rpc;

    /** 收到的协议行。 */
    private final List<ScriptProtocol.Message> received = new ArrayList<ScriptProtocol.Message>();

    /** 子进程退出闩锁。 */
    private CountDownLatch exited;

    /**
     * 关闭子进程，避免用例失败时留下残留进程。
     */
    @AfterEach
    void tearDown() {
        if (process != null) {
            process.close(2000, 2000);
            process = null;
        }
    }

    @Test
    @DisplayName("应能与真实子进程完成一次请求应答往返")
    void call_should_roundTripOverRealPipe() {
        ScriptRpc rpc = start("normal");

        JsonNode result = rpc.call(ScriptProtocol.METHOD_INITIALIZE, ScriptJson.tree("{}"), 10_000);

        assertNotNull(result);
        assertEquals("echo", result.get("scripts").get(0).get("script").asText());
    }

    @Test
    @DisplayName("环境变量应被完整替换，而不是叠加继承的环境")
    void start_should_replaceEnvironment_when_whitelistIsGiven() {
        ScriptRpc rpc = start("normal");

        JsonNode result = rpc.call(ScriptProtocol.METHOD_INITIALIZE, ScriptJson.tree("{}"), 10_000);

        // 白名单里给的变量在；没给的（HOME 一定存在于父进程）必须为空——
        // 否则「白名单」只是名义上的，脚本仍然拿到了内核进程的全套环境
        assertEquals("在", result.get("marker").asText());
        assertTrue(result.get("home").isNull(), String.valueOf(result.get("home")));
    }

    @Test
    @DisplayName("子进程误打印的脏行应被丢弃，且不影响后续帧")
    void call_should_survive_when_childPrintsNoise() throws Exception {
        ScriptRpc rpc = start("noise");

        // 脏行到达顺序不定，先给它一点时间落进会话，再发真正的请求
        Thread.sleep(300);
        JsonNode result = rpc.call(ScriptProtocol.METHOD_STATUS, ScriptJson.tree("{}"), 10_000);

        assertNotNull(result);
        assertEquals("running", result.get("state").asText());
        // 脏行不计数、不失败：会话依然可用就是全部要求
        assertEquals(0, rpc.lateResponseCount());
    }

    @Test
    @DisplayName("关闭应先送 shutdown，再确认进程真的退出")
    void close_should_shutdownAndConfirmExit() throws Exception {
        ScriptRpc rpc = start("normal");
        rpc.call(ScriptProtocol.METHOD_INITIALIZE, ScriptJson.tree("{}"), 10_000);

        rpc.call(ScriptProtocol.METHOD_SHUTDOWN, ScriptJson.tree("{}"), 10_000);

        assertTrue(exited.await(5, TimeUnit.SECONDS), "子进程没有自行退出");
        process.close(2000, 2000);
        assertFalse(process.isAlive());
    }

    @Test
    @DisplayName("子进程不理会关闭指令时，关闭方应动手收尾并确认它真的没了")
    void close_should_forceTerminate_when_childIgnoresShutdown() throws Exception {
        ScriptRpc rpc = start("ignore-shutdown");
        rpc.call(ScriptProtocol.METHOD_INITIALIZE, ScriptJson.tree("{}"), 10_000);

        process.close(300, 3000);

        assertFalse(process.isAlive());
        assertTrue(exited.await(3, TimeUnit.SECONDS), "强制关闭后应收到退出回调");
    }

    @Test
    @DisplayName("进程消失后写入应立刻失败，而不是静默丢弃")
    void send_should_failImmediately_when_processIsGone() throws Exception {
        ScriptRpc rpc = start("normal");
        rpc.call(ScriptProtocol.METHOD_INITIALIZE, ScriptJson.tree("{}"), 10_000);

        process.close(2000, 2000);

        // 关掉之后再写：必须报连接不可用，而不是假装写进去了——后者会让调用方一直等到超时
        assertThrows(ScriptConnectionException.class,
                () -> rpc.call(ScriptProtocol.METHOD_STATUS, ScriptJson.tree("{}"), 2000));
    }

    @Test
    @DisplayName("命令行解析失败应带出可读原因，而不是超时")
    void start_should_failFast_when_executableIsMissing() {
        ScriptProcessFactory factory = ScriptProcessFactory.osProcess(
                Arrays.asList("/definitely/not/a/real/binary", "--x"),
                new LinkedHashMap<String, String>(), Paths.get("."));

        assertThrowsJellyfish(() -> factory.start(line -> { }, code -> { }));
    }

    /**
     * 启动子进程并装配协议会话。
     * <p>
     * 会话必须等进程起来之后再建（它要拿到进程句柄才能发送），而进程的读回调又必须在构造时就交给它，
     * 因此中间放一个槽位把回帧转交给会话。这不是绕路：真实生产代码里 {@code ScriptGateway}
     * 用的是同一套顺序——先造会话、后起进程、再用闭包把两者接上。
     *
     * @param mode 回声子进程的运行模式
     * @return 协议会话
     */
    private ScriptRpc start(String mode) {
        exited = new CountDownLatch(1);
        Map<String, String> environment = new LinkedHashMap<String, String>();
        environment.put("JELLYFISH_TEST_MARKER", "在");
        java.util.concurrent.atomic.AtomicReference<ScriptRpc> slot =
                new java.util.concurrent.atomic.AtomicReference<ScriptRpc>();
        process = ScriptProcessFactory.osProcess(command(mode), environment, Paths.get("."))
                .start(line -> {
                    ScriptRpc current = slot.get();
                    if (current != null) {
                        current.accept(line);
                    }
                }, code -> exited.countDown());
        rpc = new ScriptRpc(process::send, message -> {
            synchronized (received) {
                received.add(message);
            }
        });
        slot.set(rpc);
        return rpc;
    }

    /**
     * 拼子进程命令行。
     *
     * @param mode 运行模式
     * @return 命令与参数
     */
    private static List<String> command(String mode) {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        return Arrays.asList(java, "-cp", testClasspath(), EchoGatewayProcess.class.getName(), mode);
    }

    /**
     * 取子 JVM 要用的类路径。
     * <p>
     * 优先用 Surefire 给出的完整类路径：它默认把类路径塞进一个只有清单的小 jar 里，
     * {@code java.class.path} 拿到的是那个 jar 本身，直接传给子 JVM 会找不到测试类。
     *
     * @return 类路径
     */
    private static String testClasspath() {
        return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    }

    /**
     * 断言启动失败并带出可读原因。
     *
     * @param action 待执行动作
     */
    private static void assertThrowsJellyfish(Runnable action) {
        zcd.jellyfish.api.JellyfishException failure =
                assertThrows(zcd.jellyfish.api.JellyfishException.class, action::run);
        assertNotNull(failure.getMessage());
        assertTrue(failure.getMessage().contains("启动"), failure.getMessage());
    }
}
