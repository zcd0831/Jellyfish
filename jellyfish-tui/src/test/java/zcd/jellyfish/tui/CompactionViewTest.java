package zcd.jellyfish.tui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.extension.CompactionTrigger;
import zcd.jellyfish.core.compact.ConversationCompactor;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.infra.session.SessionDefaults;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link CompactionView} 的单元测试：钉住「什么时候该提示、什么时候不该提示」。
 * <p>
 * 会话用真实 {@link SessionManager} 建（压缩状态只能从它的变更入口产生），状态快照直接构造。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("压缩在界面上的一层")
class CompactionViewTest {

    /** agent 门面，仅用于构造真实 SessionManager。 */
    @Mock
    private AgentManager agentManager;

    /** 通知发布入口，仅用于构造真实 SessionManager。 */
    @Mock
    private EventPublisher events;

    /** 真实会话域服务：压缩状态只能经它产生。 */
    private SessionManager sessions;

    @BeforeEach
    void setUp() {
        sessions = new SessionManager(agentManager, events, new ExtensionRegistry(new TypeRegistry()), new SessionDefaults());
    }

    @Test
    @DisplayName("从未汇报过时首帧只对表不报：启动时那条状态可能已经落定很久了")
    void sync_should_notNotice_onFirstFrame() {
        CompactionView view = new CompactionView();

        assertNull(view.sync("s-1", ConversationCompactor.State.done("已压缩 3 条更早的消息", CompactionTrigger.MANUAL)));
    }

    @Test
    @DisplayName("从压缩中落到成功：贴一条 INFO 提示")
    void sync_should_noticeOnce_when_runningBecomesDone() {
        CompactionView view = new CompactionView();
        view.sync("s-1", ConversationCompactor.State.running(CompactionTrigger.MANUAL));

        CompactionView.Notice notice = view.sync("s-1", ConversationCompactor.State.done("已压缩 3 条更早的消息", CompactionTrigger.MANUAL));

        assertNotNull(notice);
        assertEquals(ShellNotice.Kind.INFO, notice.getKind());
        assertEquals("压缩完成：已压缩 3 条更早的消息", notice.getText());
    }

    @Test
    @DisplayName("从压缩中落到失败：贴一条 ERROR 提示，带失败原因")
    void sync_should_noticeFailure_when_runningBecomesFailed() {
        CompactionView view = new CompactionView();
        view.sync("s-1", ConversationCompactor.State.running(CompactionTrigger.MANUAL));

        CompactionView.Notice notice = view.sync("s-1", ConversationCompactor.State.failed("供应商 500", CompactionTrigger.MANUAL));

        assertNotNull(notice);
        assertEquals(ShellNotice.Kind.ERROR, notice.getKind());
        assertEquals("压缩失败：供应商 500", notice.getText());
    }

    @Test
    @DisplayName("同一次跃迁只报一次：第二帧看到的还是 DONE，不该再贴一遍")
    void sync_should_notRepeatNotice_onFollowingFrames() {
        CompactionView view = new CompactionView();
        view.sync("s-1", ConversationCompactor.State.running(CompactionTrigger.MANUAL));
        view.sync("s-1", ConversationCompactor.State.done("好了", CompactionTrigger.MANUAL));

        assertNull(view.sync("s-1", ConversationCompactor.State.done("好了", CompactionTrigger.MANUAL)));
    }

    @Test
    @DisplayName("压缩中不变或回到空闲时不报：没有跃迁就没有事件")
    void sync_should_notNotice_withoutTransition() {
        CompactionView view = new CompactionView();
        view.sync("s-1", ConversationCompactor.State.running(CompactionTrigger.MANUAL));

        assertNull(view.sync("s-1", ConversationCompactor.State.running(CompactionTrigger.MANUAL)));
        assertNull(view.sync("s-1", ConversationCompactor.State.idle()));
    }

    @Test
    @DisplayName("切换会话只对表不报：切走再回来时那条 DONE 已经过期了")
    void sync_should_notNotice_when_sessionSwitched() {
        CompactionView view = new CompactionView();
        view.sync("s-1", ConversationCompactor.State.running(CompactionTrigger.MANUAL));

        assertNull(view.sync("s-2", ConversationCompactor.State.done("别的会话压完了", CompactionTrigger.MANUAL)));

        // 回到原会话也不补报：此刻它与正在显示的会话已经是两个世界
        assertNull(view.sync("s-1", ConversationCompactor.State.done("别的会话压完了", CompactionTrigger.MANUAL)));
    }

    @Test
    @DisplayName("压缩中状态栏显示「压缩中…」，压过之后显示已压缩条数")
    void label_should_reflectState() {
        Session session = compactedSession(3);

        assertEquals("   压缩中…", CompactionView.label(session, ConversationCompactor.State.running(CompactionTrigger.MANUAL)));
        assertEquals("   已压缩 3 条", CompactionView.label(session, ConversationCompactor.State.done("好了", CompactionTrigger.MANUAL)));
    }

    @Test
    @DisplayName("未压缩与没有会话都不显示标记")
    void label_should_beBlank_when_notCompacted() {
        assertEquals("", CompactionView.label(sessionWith(3), ConversationCompactor.State.done("好了", CompactionTrigger.MANUAL)));
        assertEquals("", CompactionView.label(null, ConversationCompactor.State.done("好了", CompactionTrigger.MANUAL)));
    }

    @Test
    @DisplayName("已压缩条数按边界在消息列表里的位置现算，不另存一份可能撒谎的数字")
    void coveredMessages_should_beDerivedFromBoundary() {
        assertEquals(2, CompactionView.coveredMessages(compactedSession(2)));
        assertEquals(0, CompactionView.coveredMessages(sessionWith(4)));
        assertEquals(0, CompactionView.coveredMessages(null));
    }

    @Test
    @DisplayName("自动压缩的结果必须自报来源：用户没敲命令，花的却是他的额度")
    void sync_should_markAutoOrigin_when_triggerIsAuto() {
        CompactionView view = new CompactionView();
        view.sync("s-1", ConversationCompactor.State.running(CompactionTrigger.AUTO));

        CompactionView.Notice done = view.sync("s-1",
                ConversationCompactor.State.done("已压缩 12 条更早的消息", CompactionTrigger.AUTO));

        // 失败那条要用另一个视图：同一个视图上「已落定 → 失败」不是跃迁，本就不该报
        CompactionView another = new CompactionView();
        another.sync("s-1", ConversationCompactor.State.running(CompactionTrigger.AUTO));
        CompactionView.Notice failed = another.sync("s-1",
                ConversationCompactor.State.failed("供应商 500", CompactionTrigger.AUTO));

        assertEquals("已自动压缩：已压缩 12 条更早的消息", done.getText());
        assertEquals("自动压缩失败：供应商 500", failed.getText());
    }

    @Test
    @DisplayName("丢弃条数要进状态栏：那是真正消失的数据，不该只出现在 /status 里")
    void label_should_showDroppedCount() {
        Session session = sessionWith(4);
        sessions.applyCompaction(session.getSessionId(), "摘要",
                sessions.messagesOf(session.getSessionId()).get(2).getMessageId(), 2);

        assertEquals("   已压缩 3 条（丢弃 2 条）",
                CompactionView.label(session, ConversationCompactor.State.done("好了", CompactionTrigger.MANUAL)));
        assertEquals(2, CompactionView.droppedMessages(session));
        assertEquals(0, CompactionView.droppedMessages(null));
        assertEquals(0, CompactionView.droppedMessages(sessionWith(2)));
    }

    /**
     * 建一个带若干消息的会话。
     *
     * @param count 消息条数
     * @return 会话运行态
     */
    private Session sessionWith(int count) {
        Session session = sessions.createDefault();
        for (int index = 1; index <= count; index++) {
            sessions.appendMessage(session.getSessionId(), LlmMessage.user("第 " + index + " 条"), null);
        }
        return session;
    }

    /**
     * 建一个「已压缩前 count 条」的会话。
     *
     * @param count 被摘要覆盖的条数
     * @return 会话运行态
     */
    private Session compactedSession(int count) {
        Session session = sessionWith(count + 1);
        sessions.applyCompaction(session.getSessionId(), "摘要",
                sessions.messagesOf(session.getSessionId()).get(count - 1).getMessageId(), 0);
        return session;
    }
}
