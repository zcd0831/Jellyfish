package zcd.jellyfish.script.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;

import java.util.HashMap;
import java.util.Map;

/**
 * 一次脚本进程会话的请求/应答配对：给每个请求发一个自增 id，等应答或超时。
 * <p>
 * <b>它是协议层唯一有状态的地方</b>。{@link ScriptProtocol} 只做文本翻译，而「谁在等哪个 id」
 * 「等到什么时候」「连接断了怎么唤醒所有人」这些只有一处能说清楚，就放在这里。
 * 换句话说，协议形状可以完全离线单测，并发问题只在这一个类里面对。
 * <p>
 * <b>为什么用监视器而不是异步回调</b>：脚本调用天然是同步的——{@code ReActLooper} 调工具、
 * 阻塞等结果，没有「先干别的、结果到了再通知」的需求。用 {@code CompletableFuture} 会把
 * 一段直白的等待/通知写成回调链，收益只在未来可能出现的并发发起上；
 * 而并发发起已经由「多个线程各自 {@code call}」天然支持（等待表是共享的）。
 * <p>
 * <b>读方必须只有一个线程</b>：{@link #accept(String)} 由读取线程串行调用。
 * <b>写方可以是多个线程</b>：{@link Sender} 的实现方负责保证整行原子。
 * <p>
 * <b>失败只有三种，且互斥</b>：
 * <ul>
 *     <li>网关回报错误 → {@link ScriptCallException}（带错误码）；</li>
 *     <li>超时 → {@link ScriptTimeoutException}，并把该 id 从等待表移除，迟到响应按 id 丢弃；</li>
 *     <li>连接断开 → {@link ScriptConnectionException}，并唤醒全部等待者。</li>
 * </ul>
 * 第三种一次性唤醒所有人是有意的：进程已经没了，让其余调用各自等到超时，只会把一次故障放大成
 * N 个超时周期。
 * <p>
 * 线程安全。
 *
 * @author zcd
 */
public final class ScriptRpc {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ScriptRpc.class);

    /** 完整告警脏行的上限，超过后只记 DEBUG。 */
    private static final int MAX_FULL_ALERTS = 5;

    /**
     * 协议文本的发送方。
     * <p>
     * <b>为什么是接口而不是直接持有 {@code ScriptProcess}</b>：进程是「一代」的——它会退出、会被重建。
     * 若本类直接持有进程，网关就必须在「进程还没建好」与「进程已经换了一代」之间做同步，
     * 而这恰好制造出一个死锁窗口：进程退出回调若需要网关的锁，就会与正在等待初始化的调用线程互相等。
     * 让本类只依赖一个发送动作、由网关在锁外解析「当前是哪一代进程」，
     * 退出回调就能无条件地把失败送达它该送达的那一代，不需要任何锁。
     * <p>
     * 实现方<b>必须保证整行原子</b>：本类不为此加锁，因为并发发送的线程数不该由协议层假设。
     */
    @FunctionalInterface
    public interface Sender {

        /**
         * 发送一帧协议文本。
         *
         * @param line 一帧协议文本，不可为 {@code null}
         * @throws zcd.jellyfish.api.JellyfishException 连接不可用时抛出
         */
        void send(String line);
    }

    /** 上行消息的接收方。 */
    public interface Listener {

        /**
         * 收到一条需要处理的上行消息（请求或通知）。
         * <p>
         * 本方法在读取线程上被调用，因此实现方<b>不要阻塞</b>（长任务应交给自己的线程池），
         * 也不要把异常抛出去——抛出去只会打断读取线程，让整门语言失联。
         *
         * @param message 消息，不可为 {@code null}
         */
        void onIncoming(ScriptProtocol.Message message);
    }

    /** 协议文本的发送方。 */
    private final Sender sender;

    /** 上行消息接收方。 */
    private final Listener listener;

    /** 保护等待表与连接状态的监视器。 */
    private final Object lock = new Object();

    /** 等待应答的表：id → 等待位。 */
    private final Map<Long, Pending> pending = new HashMap<Long, Pending>();

    /** 下一个消息 id。 */
    private long nextId = 1;

    /** 连接是否已关闭。 */
    private boolean closed;

    /** 连接断开原因；{@code null} 表示连接仍可用。 */
    private JellyfishException failure;

    /** 被丢弃的迟到响应数。 */
    private int lateResponses;

    /** 已告警过的脏行数，仅用于抑制重复告警。 */
    private int warnedDirtyLines;

    /**
     * 构造 RPC 会话。
     *
     * @param sender   协议文本发送方，不可为 {@code null}
     * @param listener 上行消息接收方，不可为 {@code null}
     */
    public ScriptRpc(Sender sender, Listener listener) {
        this.sender = sender;
        this.listener = listener;
    }

    /**
     * 同步调用一次，直到拿到应答、超时或连接断开。
     *
     * @param method        方法名，不可为空白
     * @param params        参数，可为 {@code null}
     * @param timeoutMillis 超时毫秒数；{@code <= 0} 表示不超时（仍会被连接断开唤醒）
     * @return 成功结果载荷；应答没有 {@code result} 字段时为 {@code null}
     * @throws ScriptCallException       网关回报了协议错误时抛出
     * @throws ScriptTimeoutException    超时未收到应答时抛出
     * @throws ScriptConnectionException 连接不可用时抛出
     */
    public JsonNode call(String method, JsonNode params, long timeoutMillis) {
        long id;
        Pending slot = new Pending();
        synchronized (lock) {
            requireUsable();
            id = nextId++;
            pending.put(Long.valueOf(id), slot);
        }
        try {
            sender.send(ScriptProtocol.request(id, method, params));
        } catch (JellyfishException e) {
            // 发送失败只有一种解释：连接已经坏了。统一按连接故障上报，否则「谁先发现」
            // 会决定调用方拿到哪种异常，上层就无法靠类型判断该怎么处置了。
            // 等待位也必须先摘掉，否则它会永远留在表里，而「泄漏的等待位」在下一次
            // 同 id 复用时表现为「拿到别人的结果」这种极难查的错误
            ScriptConnectionException failure = new ScriptConnectionException(
                    "脚本运行时连接不可用（发送协议帧失败）: " + method + " — " + e.getMessage(), e);
            synchronized (lock) {
                pending.remove(Long.valueOf(id));
                failLocked(failure);
            }
            throw failure;
        }
        return await(id, slot, method, timeoutMillis);
    }

    /**
     * 发送一个通知，不等待应答。
     * <p>
     * 通知没有等待位，因此<b>发出去就结束了</b>：网关是否处理成功无法得知，
     * 这正是「事件可丢」在协议层的体现。
     *
     * @param method 方法名，不可为空白
     * @param params 参数，可为 {@code null}
     * @throws ScriptConnectionException 连接不可用时抛出
     */
    public void notify(String method, JsonNode params) {
        synchronized (lock) {
            requireUsable();
        }
        sender.send(ScriptProtocol.notification(method, params));
    }

    /**
     * 应答一条上行请求。
     *
     * @param id     被应答的消息 id
     * @param result 结果载荷，可为 {@code null}
     */
    public void respond(long id, JsonNode result) {
        sendQuietly(ScriptProtocol.response(id, result));
    }

    /**
     * 拒绝一条上行请求。
     *
     * @param id      被应答的消息 id
     * @param code    错误码
     * @param message 错误描述
     */
    public void respondError(long id, int code, String message) {
        sendQuietly(ScriptProtocol.errorResponse(id, code, message));
    }

    /**
     * 接收并分发一帧文本，由读取线程串行调用。
     * <p>
     * <b>无法解析的行只告警、不失败、也不计数</b>：误打印不是脚本的业务失败，
     * 把它计进任何失败账目都会让「脚本里多打了一行日志」演变成「工具被隔离」。
     * 唯一的例外是告警本身要限流（见 {@link #MAX_FULL_ALERTS}），否则一个循环打印的脚本
     * 会把内核日志刷满，反而让真正的错误淹没其中——那是纯粹的日志噪声，不参与任何判定。
     * 空行连告警都不需要：它不是「非 JSON 行」，只是一个换行。
     *
     * @param line 帧文本，可为 {@code null}
     */
    public void accept(String line) {
        if (line == null || line.trim().isEmpty()) {
            return;
        }
        ScriptProtocol.Message message = ScriptProtocol.parse(line);
        if (message == null) {
            warnDirtyLine(line);
            return;
        }
        if (message.isResponse()) {
            dispatchResponse(message);
            return;
        }
        dispatchIncoming(message);
    }

    /**
     * 标记连接断开并唤醒全部等待者。
     * <p>
     * 幂等：重复调用只保留第一个原因（最接近根因的那个）。
     *
     * @param cause 断开原因，不可为 {@code null}
     */
    public void fail(JellyfishException cause) {
        synchronized (lock) {
            failLocked(cause);
        }
    }

    /**
     * 关闭会话：拒绝后续请求并唤醒全部等待者。
     * <p>
     * 不关闭子进程——那是发送方实现方的职责，本类只管「不再有人等应答」。
     */
    public void close() {
        synchronized (lock) {
            if (!closed) {
                closed = true;
                lock.notifyAll();
            }
        }
    }

    /**
     * 获取被丢弃的迟到响应数。
     *
     * @return 迟到响应数
     */
    public int lateResponseCount() {
        synchronized (lock) {
            return lateResponses;
        }
    }

    /**
     * 等待应答。
     *
     * @param id            消息 id
     * @param slot          等待位
     * @param method        方法名，仅用于报错文案
     * @param timeoutMillis 超时毫秒数
     * @return 成功结果载荷
     */
    private JsonNode await(long id, Pending slot, String method, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        synchronized (lock) {
            while (!slot.done && failure == null && !closed) {
                long remaining = timeoutMillis <= 0 ? 0 : deadline - System.currentTimeMillis();
                if (timeoutMillis > 0 && remaining <= 0) {
                    break;
                }
                try {
                    lock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    pending.remove(Long.valueOf(id));
                    throw new JellyfishException("脚本调用被中断: " + method, e);
                }
            }
            pending.remove(Long.valueOf(id));
            if (slot.done) {
                return resultOf(slot, method, id);
            }
            if (failure != null) {
                throw new ScriptConnectionException("脚本调用失败（连接不可用）: " + method
                        + "，原因: " + failure.getMessage(), failure);
            }
            if (closed) {
                throw new ScriptConnectionException("脚本调用失败（运行时已关闭）: " + method, null);
            }
        }
        throw new ScriptTimeoutException("脚本调用超时（已等待 " + timeoutMillis + " ms）: " + method, timeoutMillis);
    }

    /**
     * 取出等待位的成功结果，失败则抛异常。
     *
     * @param slot   等待位
     * @param method 方法名
     * @param id     消息 id
     * @return 成功结果载荷
     */
    private JsonNode resultOf(Pending slot, String method, long id) {
        if (slot.errorCode != 0) {
            throw new ScriptCallException(slot.errorCode,
                    "脚本调用失败（" + slot.errorCode + "）: " + method + " — " + slot.errorMessage);
        }
        LOG.debug("脚本调用完成: method={} id={}", method, Long.valueOf(id));
        return slot.result;
    }

    /**
     * 把应答交给等待者。
     *
     * @param message 应答消息
     */
    private void dispatchResponse(ScriptProtocol.Message message) {
        long id = message.id().longValue();
        synchronized (lock) {
            Pending slot = pending.get(Long.valueOf(id));
            if (slot == null) {
                // 超时已经把这个 id 摘掉了：迟到响应按 id 丢弃，不做补偿
                lateResponses++;
                lock.notifyAll();
                LOG.warn("脚本协议收到无等待者的应答，已丢弃: id={}", message.id());
                return;
            }
            slot.done = true;
            slot.result = message.result();
            slot.errorCode = message.errorCode();
            slot.errorMessage = message.errorMessage();
            lock.notifyAll();
        }
    }

    /**
     * 把请求/通知交给接收方。
     *
     * @param message 消息
     */
    private void dispatchIncoming(ScriptProtocol.Message message) {
        try {
            listener.onIncoming(message);
        } catch (RuntimeException e) {
            // 接收方抛错不能打断读取线程：那会让整门语言失联，而问题可能只是某一次通知没处理
            LOG.warn("处理脚本上行消息 {} 时失败: {}", message.method(), e.toString());
        }
    }

    /**
     * 记录断开原因并唤醒等待者。
     *
     * @param cause 断开原因
     */
    private void failLocked(JellyfishException cause) {
        if (failure == null && !closed) {
            failure = cause;
            lock.notifyAll();
        }
    }

    /**
     * 校验连接可用。
     *
     * @throws ScriptConnectionException 连接已断开或已关闭时抛出
     */
    private void requireUsable() {
        if (failure != null) {
            throw new ScriptConnectionException("脚本运行时连接已断开: " + failure.getMessage(), failure);
        }
        if (closed) {
            throw new ScriptConnectionException("脚本运行时已关闭", null);
        }
    }

    /**
     * 尽力发送一帧，失败只告警。
     * <p>
     * 应答上行请求时用：脚本已经发出去了，Java 侧发不出应答只能算丢了一条消息，
     * 不该让读取线程因为一次应答失败而中断。
     *
     * @param line 帧文本
     */
    private void sendQuietly(String line) {
        try {
            sender.send(line);
        } catch (JellyfishException e) {
            LOG.warn("应答脚本上行请求失败，已放弃: {}", e.getMessage());
        }
    }

    /**
     * 记录一条脏行，超过阈值后只在 DEBUG 输出。
     *
     * @param line 脏行原文
     */
    private void warnDirtyLine(String line) {
        boolean report;
        synchronized (lock) {
            report = warnedDirtyLines < MAX_FULL_ALERTS;
            if (report) {
                warnedDirtyLines++;
            }
        }
        if (report) {
            LOG.warn("脚本协议收到无法识别的行，已丢弃: {}", abbreviate(line));
        } else {
            LOG.debug("脚本协议收到无法识别的行，已丢弃（后续同类不再告警）: {}", abbreviate(line));
        }
    }

    /**
     * 截断过长文本，避免一行误打印把日志刷满。
     *
     * @param line 原始文本，可为 {@code null}
     * @return 截断后的文本
     */
    private static String abbreviate(String line) {
        if (line == null) {
            return "null";
        }
        return line.length() <= 200 ? line : line.substring(0, 200) + "...(" + line.length() + " 字符)";
    }

    /**
     * 一个请求的等待位。
     * <p>
     * 字段直接由持有 {@code lock} 的一方读写，因此不需要 volatile。
     */
    private static final class Pending {

        /** 是否已收到应答。 */
        private boolean done;

        /** 成功结果。 */
        private JsonNode result;

        /** 错误码。 */
        private int errorCode;

        /** 错误描述。 */
        private String errorMessage;
    }
}
