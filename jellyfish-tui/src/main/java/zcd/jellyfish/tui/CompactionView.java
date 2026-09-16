package zcd.jellyfish.tui;

import zcd.jellyfish.api.extension.CompactionTrigger;
import zcd.jellyfish.core.compact.ConversationCompactor;
import zcd.jellyfish.infra.session.Session;

import java.util.Objects;

/**
 * 压缩在界面上的那一层：状态栏标记 + 「压完了」的一次性提示。
 * <p>
 * <b>为什么单独一个类而不是塞在 {@code TuiApp} 里</b>：这层逻辑有一处不显眼但会出错的判断——
 * <b>切换会话时不能补提示</b>。压缩期间用户可能切走再回来，回来时看到的是 {@code DONE}，
 * 那一下不是「刚发生的」；把它当跃迁贴出来，会让一条几分钟前的压缩结果混进当前会话的时间线。
 * 判断本身是纯状态迁移，放在渲染方法里就只能靠人眼盯着，因此提到这里用单测钉住。
 * <p>
 * <b>只由渲染线程读写</b>：{@code compactor.status} 是线程安全的快照，但本类持有的
 * 「上一帧看到了什么」是界面状态，与 {@code ChatState} 同一条线程契约。
 *
 * @author zcd
 */
final class CompactionView {

    /** 上一帧看到的压缩状态。 */
    private ConversationCompactor.State rendered = ConversationCompactor.State.idle();

    /** 上一帧看到的压缩状态属于哪个会话。 */
    private String renderedSessionId;

    /**
     * 对表一帧的压缩状态，必要时给出一次性提示。
     *
     * @param sessionId 当前会话标识，可为 {@code null}
     * @param current   本帧读到的压缩状态，不可为 {@code null}
     * @return 需要贴出的提示；无需提示时返回 {@code null}
     */
    Notice sync(String sessionId, ConversationCompactor.State current) {
        Objects.requireNonNull(current, "current must not be null");
        if (!Objects.equals(sessionId, renderedSessionId)) {
            // 会话换了：只对表，不报。这条状态可能已经落定很久了
            renderedSessionId = sessionId;
            rendered = current;
            return null;
        }
        // 只认「压缩中 → 成功 / 失败」这两个终态：其它迁移要么不会发生（RUNNING → IDLE），
        // 要么不该被当成结果（本来就空闲）
        Notice notice = noticeOf(current);
        rendered = current;
        return notice;
    }

    /**
     * 判断本帧是否构成一条「压缩结果」。
     *
     * @param current 本帧读到的压缩状态
     * @return 待贴出的提示；不构成结果时返回 {@code null}
     */
    private Notice noticeOf(ConversationCompactor.State current) {
        if (!rendered.isRunning()) {
            return null;
        }
        boolean auto = current.getTrigger() == CompactionTrigger.AUTO;
        if (current.getStatus() == ConversationCompactor.Status.DONE) {
            // 自动压缩必须自报来源：用户没敲任何命令，花的却是他的额度
            return new Notice((auto ? "已自动压缩：" : "压缩完成：") + current.getMessage(),
                    ShellNotice.Kind.INFO);
        }
        if (current.getStatus() == ConversationCompactor.Status.FAILED) {
            // 失败同样要写清来源——自动压缩失败时，用户根本不知道发生过这件事
            return new Notice((auto ? "自动压缩失败：" : "压缩失败：") + current.getMessage(),
                    ShellNotice.Kind.ERROR);
        }
        return null;
    }

    /**
     * 渲染状态栏上的压缩标记。
     *
     * @param session 当前会话，可为 {@code null}
     * @param state   本帧读到的压缩状态，不可为 {@code null}
     * @return 标记文本；无需标记时返回空串
     */
    static String label(Session session, ConversationCompactor.State state) {
        if (state.isRunning()) {
            return "   压缩中…";
        }
        int covered = coveredMessages(session);
        if (covered <= 0) {
            return "";
        }
        int dropped = droppedMessages(session);
        // 丢弃条数一起标出来：那是真正消失的数据，不该只出现在 /status 里
        return dropped <= 0 ? "   已压缩 " + covered + " 条"
                : "   已压缩 " + covered + " 条（丢弃 " + dropped + " 条）";
    }

    /**
     * 取当前会话压缩时被直接丢弃的条数。
     *
     * @param session 当前会话，可为 {@code null}
     * @return 条数；未压缩时为 {@code 0}
     */
    static int droppedMessages(Session session) {
        if (session == null || session.getCompaction() == null) {
            return 0;
        }
        return session.getCompaction().getDroppedMessageCount();
    }

    /**
     * 数出当前会话里已被摘要覆盖的消息条数。
     * <p>
     * 现算而不是读一个存下来的数字：边界消息一旦不在会话里（手工改过文件），
     * 存下来的条数就会撒谎，而现算的结果是 0——那种情况下按「未压缩」显示才是真的。
     *
     * @param session 当前会话，可为 {@code null}
     * @return 条数；未压缩或边界失效时返回 {@code 0}
     */
    static int coveredMessages(Session session) {
        if (session == null || session.getCompaction() == null) {
            return 0;
        }
        return session.indexOfMessage(session.getCompaction().getBoundaryMessageId()) + 1;
    }

    /**
     * 一条待贴出的提示。
     * <p>
     * 不可变。
     */
    static final class Notice {

        /** 提示文本。 */
        private final String text;

        /** 提示语义。 */
        private final ShellNotice.Kind kind;

        /**
         * 构造提示。
         *
         * @param text 提示文本，不可为 {@code null}
         * @param kind 提示语义，不可为 {@code null}
         */
        Notice(String text, ShellNotice.Kind kind) {
            this.text = text;
            this.kind = kind;
        }

        /**
         * 获取提示文本。
         *
         * @return 提示文本
         */
        String getText() {
            return text;
        }

        /**
         * 获取提示语义。
         *
         * @return 提示语义
         */
        ShellNotice.Kind getKind() {
            return kind;
        }
    }
}
