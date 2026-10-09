package zcd.jellyfish.infra.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.SessionSnapshot;
import zcd.jellyfish.infra.llm.LlmMessage;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「取快照必须是一次一致投影」的确定性用例。
 * <p>
 * <b>它钉的是什么</b>：{@link SessionSnapshots#capture} 逐个读会话的同步 getter，读消息与读用量之间
 * 没有任何东西把它们绑在一起。别的线程在那一段里推进过一次状态，快照里就会出现自相矛盾的一对字段
 * ——「用量说调过两次模型，消息列表里只有一条 assistant」——而它会随落盘留在磁盘上。
 * {@link Session#captureSnapshot()} 把整份读取放进同一段临界区，这一条就是用例要区分的那件事。
 * <p>
 * <b>怎么做到确定性</b>：那一段空隙在真实代码里没有可注入的停顿点，因此用
 * {@link Session#betweenSnapshotReads}（包私有接缝，缺省 no-op）把「另一个线程去推进状态」摆在
 * 两次读之间，并给它一个有界等待。于是持锁与不持锁的差别变成可观察的事实：
 * <ul>
 *   <li>持锁：那个线程被实例锁挡在门外，等待必然超时，快照里两个字段来自同一个瞬间；</li>
 *   <li>不持锁：那个线程立刻写完，后读的用量比先读的消息「新」，快照自相矛盾。</li>
 * </ul>
 * <b>注意注入的状态推进必须来自另一个线程</b>：同一个线程里的注入在 {@code synchronized} 面前是
 * 可重入的（锁归它自己），那样两条路径表现完全相同、用例也就失去区分力。
 * <p>
 * 顺带一提：主用例调的是 {@link Session#captureSnapshot()} 本身，因此它同时守住了「那个方法上的
 * {@code synchronized} 别被去掉」——去掉它，写线程立刻就能进来，用例当场变红。
 *
 * @author zcd
 */
@DisplayName("会话快照的一致投影")
class SessionSnapshotConsistencyTest {

    /** 等另一个线程「插进来」的上限；持锁时它必然走满这个时长（那正是被挡住的表现）。 */
    private static final long WRITER_WAIT_MILLIS = 200L;

    /** 接缝的初始值：什么都不做。 */
    private static final Runnable NO_OP = () -> {
    };

    @Test
    @DisplayName("持锁取快照：别的线程插不进「读完消息、还没读用量」之间")
    void captureSnapshot_should_blockWriter_betweenMessageAndUsageReads() {
        Session session = sessionWithOneAssistantMessage();
        WriterProbe probe = installWriterProbe(session);

        SessionSnapshot snapshot;
        try {
            snapshot = session.captureSnapshot();
        } finally {
            // 接缝是静态的（见其注释）：用完立即复位，免得影响后续用例
            Session.betweenSnapshotReads = NO_OP;
        }

        // **这一条是本用例的核心**：它证明的不是「碰巧没插进来」，而是「它被锁挡住了」。
        // 少了它，一个自己崩掉的写线程也会让下面的自洽断言通过（假绿）
        assertFalse(probe.slippedInDuringRead.get(),
                "取快照期间写线程竟然完成了——说明读取没有持住实例锁");
        assertNull(probe.failure.get(), "写线程不该出错：" + probe.failure.get());
        // 快照自洽：调用次数只认 assistant 消息，因此两者必须来自同一个瞬间
        assertEquals(1, snapshot.getMessages().size(), "快照里的消息条数");
        assertEquals(1L, snapshot.getUsage().getLlmCalls(),
                "用量与消息列表出自同一个瞬间（快照里 1 条 assistant，用量就该是 1 次调用）");
    }

    @Test
    @DisplayName("对照：不持锁的逐个 getter 读法确实会读出矛盾的快照")
    void directCapture_should_readContradictorySnapshot_whenWriterSlipsIn() {
        // 这一条是**对照实验**，而不是「断言一个已知的坏行为」：它用同一处接缝证明下面那件事——
        // 「接缝真的能制造出矛盾」。有了它，上一条用例里「没有矛盾」才说得清是持锁换来的，
        // 而不是「注入根本没生效」；少了它，上一条可能恒绿
        Session session = sessionWithOneAssistantMessage();
        WriterProbe probe = installWriterProbe(session);

        SessionSnapshot snapshot;
        try {
            snapshot = SessionSnapshots.capture(session);
        } finally {
            Session.betweenSnapshotReads = NO_OP;
        }

        // 不持锁：写线程立刻进来了，于是「先读的消息」是旧的、「后读的用量」是新的
        assertTrue(probe.slippedInDuringRead.get(), "不持锁时写线程不该被挡住");
        assertEquals(1, snapshot.getMessages().size(), "消息是先读的，因此仍是 1 条");
        assertEquals(2L, snapshot.getUsage().getLlmCalls(),
                "用量是后读的，因此记了 2 次调用——这正是「同一份快照里两个字段互相矛盾」的样子");
        // 把矛盾写成一条一目了然的断言：这正是 Session.captureSnapshot() 要消灭的东西
        assertTrue(snapshot.getUsage().getLlmCalls() != snapshot.getMessages().size(),
                "这份快照本就该是矛盾的（用量 2 次调用 vs 消息 1 条）");
    }

    /**
     * 造一个只有一条 assistant 消息的会话。
     *
     * @return 会话运行态
     */
    private static Session sessionWithOneAssistantMessage() {
        Session session = new Session("session-1", "coder", "openai", "gpt-4o", 1_700_000_000_000L);
        session.append(SessionMessage.of(LlmMessage.assistant("第一条")));
        return session;
    }

    /**
     * 把「另一个线程在读完消息、还没读用量之间插进来」装到接缝上。
     * <p>
     * 那个线程追加一条 assistant 消息，因此它同时把 {@code llmCalls} 加一——「用量」与「消息列表」
     * 谁新谁旧一眼能看出来。等待有界：持锁时它必然超时（这正是被挡住的表现）。
     *
     * @param session 会话运行态
     * @return 探针；调用方负责在 finally 里把接缝复位
     */
    private static WriterProbe installWriterProbe(Session session) {
        WriterProbe probe = new WriterProbe();
        CountDownLatch advanced = new CountDownLatch(1);
        Session.betweenSnapshotReads = () -> {
            Thread thread = new Thread(() -> {
                try {
                    session.append(SessionMessage.of(LlmMessage.assistant("插进来的那条")));
                    probe.writerCompleted.set(true);
                } catch (RuntimeException | Error failure) {
                    probe.failure.set(failure);
                } finally {
                    // 无论成败都要放行，否则用例失败时会白等满整个等待上限
                    advanced.countDown();
                }
            }, "snapshot-writer");
            thread.setDaemon(true);
            thread.start();
            try {
                advanced.await(WRITER_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            // **取样必须在还持锁时做**：等到 capture 返回再读 writerCompleted，读到的一定是 true
            // （锁一放开写线程就进来了，那是设计使然），那样这条探针就废了
            probe.slippedInDuringRead.set(probe.writerCompleted.get());
        };
        return probe;
    }

    /**
     * 写线程探针：它是否在读取期间完成了、它是否出错。
     */
    private static final class WriterProbe {

        /** 写线程是否完成（**只在还持锁时读**）。 */
        private final AtomicBoolean writerCompleted = new AtomicBoolean();

        /** 写线程完成这件事在「读取期间」是否已经发生。 */
        private final AtomicBoolean slippedInDuringRead = new AtomicBoolean();

        /** 写线程的异常（有的话）。 */
        private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
    }
}
