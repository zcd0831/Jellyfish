package zcd.jellyfish.script.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.script.ScriptJson;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求/应答配对的单元测试。
 * <p>
 * 全部用例都不碰真实进程：发送方只是一个收集文本的列表，回帧由测试直接喂给
 * {@link ScriptRpc#accept(String)}。这样「超时」「迟到响应」「断连唤醒」这些时序场景
 * 可以被精确构造、稳定复现——而它们在真实进程上几乎无法可靠重放。
 *
 * @author zcd
 */
@DisplayName("脚本请求应答配对")
class ScriptRpcTest {

    /** 已发出的帧。 */
    private final List<String> sent = new ArrayList<String>();

    /** 上行消息接收方收到的消息数。 */
    private final List<String> incoming = new ArrayList<String>();

    /** 被测会话。 */
    private final ScriptRpc rpc = new ScriptRpc(line -> {
        synchronized (sent) {
            sent.add(line);
        }
    }, message -> incoming.add(message.method()));

    @Test
    @DisplayName("应答到达时应返回结果，并把 id 从等待表摘掉")
    void call_should_returnResult_when_responseArrives() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<JsonNode> result = new AtomicReference<JsonNode>();
        Thread caller = new Thread(() -> {
            result.set(rpc.call("invoke", null, 5000));
            done.countDown();
        });
        caller.setDaemon(true);
        caller.start();
        long id = awaitSent();
        rpc.accept(ScriptProtocol.response(id, ScriptJson.tree("{\"output\":\"ok\"}")));

        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertEquals("ok", result.get().get("output").asText());
    }

    @Test
    @DisplayName("网关回报错误时应抛带错误码的异常")
    void call_should_throwWithCode_when_gatewayReportsError() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ScriptCallException> failure = new AtomicReference<ScriptCallException>();
        Thread caller = new Thread(() -> {
            try {
                rpc.call("invoke", null, 5000);
            } catch (ScriptCallException e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        });
        caller.setDaemon(true);
        caller.start();
        long id = awaitSent();
        rpc.accept(ScriptProtocol.errorResponse(id, ScriptProtocol.CODE_SCRIPT_FAILURE, "脚本炸了"));

        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertEquals(ScriptProtocol.CODE_SCRIPT_FAILURE, failure.get().code());
        assertTrue(failure.get().isScriptFailure());
    }

    @Test
    @DisplayName("熔断与清单不一致错误应可被单独识别")
    void callException_should_exposeSpecificCodes() {
        assertTrue(new ScriptCallException(ScriptProtocol.CODE_CIRCUIT_OPEN, "x").isCircuitOpen());
        assertTrue(new ScriptCallException(ScriptProtocol.CODE_MANIFEST_MISMATCH, "x").isManifestMismatch());
        assertEquals(false, new ScriptCallException(ScriptProtocol.CODE_CIRCUIT_OPEN, "x").isScriptFailure());
    }

    @Test
    @DisplayName("超时后到达的响应应被丢弃，且不干扰后续调用")
    void accept_should_dropLateResponse_when_callerAlreadyTimedOut() throws Exception {
        long first = -1;
        try {
            rpc.call("invoke", null, 30);
        } catch (ScriptTimeoutException e) {
            assertEquals(30, e.waitedMillis());
        }
        first = awaitSent();
        long second;
        rpc.accept(ScriptProtocol.response(first, ScriptJson.tree("{\"output\":\"late\"}")));

        assertEquals(1, rpc.lateResponseCount());
        // 迟到的响应不能污染下一次调用：新请求用的 id 与它不同，因此新调用仍要等到自己的应答
        assertNotNull(rpc);
        assertThrows(ScriptTimeoutException.class, () -> rpc.call("invoke", null, 30));
        second = awaitSent();
        assertTrue(second > first);
    }

    @Test
    @DisplayName("脏行与空行都应被丢弃，且不影响后续调用")
    void accept_should_dropDirtyLines_withoutFailing() {
        rpc.accept("脚本误打印的一行");
        rpc.accept("");
        rpc.accept("{不是 JSON");
        rpc.accept(null);

        // 脏行不计数、不失败：这里能观察到的只有「会话仍然可用」
        assertThrows(ScriptTimeoutException.class, () -> rpc.call("invoke", null, 10));
    }

    @Test
    @DisplayName("连接断开应唤醒全部等待者，而不是让每个调用各自等到超时")
    void fail_should_wakeAllWaiters() throws Exception {
        List<Thread> callers = new ArrayList<Thread>();
        List<String> failures = new ArrayList<String>();
        for (int index = 0; index < 3; index++) {
            Thread caller = new Thread(() -> {
                try {
                    rpc.call("invoke", null, 60_000);
                } catch (ScriptConnectionException e) {
                    synchronized (failures) {
                        failures.add(e.getMessage());
                    }
                }
            });
            caller.setDaemon(true);
            caller.start();
            callers.add(caller);
        }
        awaitSent();
        awaitSent();
        awaitSent();
        long started = System.currentTimeMillis();
        rpc.fail(new ScriptConnectionException("网关进程已退出", null));
        for (Thread caller : callers) {
            caller.join(2000);
        }

        assertEquals(3, failures.size());
        // 必须远早于 60s 的调用超时：一次性唤醒的意义就在这里
        assertTrue(System.currentTimeMillis() - started < 3000);
    }

    @Test
    @DisplayName("断连后再调用应立刻失败，不进等待表")
    void call_should_failImmediately_when_connectionAlreadyFailed() {
        rpc.fail(new ScriptConnectionException("网关进程已退出", null));

        assertThrows(ScriptConnectionException.class, () -> rpc.call("invoke", null, 60_000));
    }

    @Test
    @DisplayName("关闭后应拒绝后续请求并唤醒等待者")
    void close_should_rejectFurtherCalls() throws Exception {
        Thread caller = new Thread(() -> assertThrows(ScriptConnectionException.class,
                () -> rpc.call("invoke", null, 60_000)));
        caller.setDaemon(true);
        caller.start();
        awaitSent();

        rpc.close();

        caller.join(2000);
        assertThrows(ScriptConnectionException.class, () -> rpc.notify("event", null));
    }

    @Test
    @DisplayName("上行消息应交由接收方处理，抛错不得打断读取")
    void accept_should_stayUsable_when_listenerThrows() {
        ScriptRpc fragile = new ScriptRpc(line -> { }, message -> {
            throw new IllegalStateException("接收方自己有 bug");
        });

        fragile.accept("{\"jsonrpc\":\"2.0\",\"method\":\"worker_state\",\"params\":{}}");
        fragile.accept("脚本误打印");

        assertNotNull(fragile);
    }

    @Test
    @DisplayName("通知与应答应区分处理")
    void accept_should_routeNotificationsToListener() {
        rpc.accept("{\"jsonrpc\":\"2.0\",\"method\":\"worker_state\",\"params\":{\"script\":\"jira\"}}");

        assertEquals(1, incoming.size());
        assertEquals("worker_state", incoming.get(0));
    }

    @Test
    @DisplayName("断连后重复 fail 应保留第一个原因")
    void fail_should_keepFirstCause_when_calledTwice() {
        rpc.fail(new ScriptConnectionException("第一次", null));
        rpc.fail(new ScriptConnectionException("第二次", null));

        ScriptConnectionException failure =
                assertThrows(ScriptConnectionException.class, () -> rpc.call("invoke", null, 10));
        assertTrue(failure.getMessage().contains("第一次"), failure.getMessage());
    }

    /**
     * 等一封已发出的帧，返回它的 id。
     * <p>
     * 用轮询而不是闩锁：调用线程与测试线程之间只共享这个列表，
     * 而这里要断言的是「帧发出去了」，用最小同步原语即可，不必引入额外约定。
     *
     * @return 最后一封帧的 id
     * @throws InterruptedException 等待被中断时抛出
     */
    private long awaitSent() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            synchronized (sent) {
                if (!sent.isEmpty()) {
                    String line = sent.get(sent.size() - 1);
                    return ScriptProtocol.parse(line).id().longValue();
                }
            }
            Thread.sleep(5);
        }
        throw new AssertionError("调用线程没有发出任何请求帧");
    }
}
