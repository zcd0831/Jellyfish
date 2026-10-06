package zcd.jellyfish.api.ask;

/**
 * 一次提问的答复。
 * <p>
 * <b>它不只回答「是什么」，还回答「为什么没有答案」</b>：提问方（工具、插件）拿到的是一个终态，
 * 必须能区分「用户选了」、「用户自己写了」、「用户放弃了」、「没人回答」、「这里根本没有人可问」——
 * 这五种处境下模型该做的下一件事完全不同（接着干 / 换个问法 / 说明自己的假设）。
 * 把后四种压成一个 {@code null} 会让提问方只能猜。
 * <p>
 * <b>答案不是权限</b>：本类型不携带任何「放行」语义。选了任何一项都不会让哪个工具获得执行权，
 * 因此把它当成审批结论使用是错的——权限只由 {@code PermissionManager} 收口。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class AskAnswer {

    /** 答复终态。 */
    private final Status status;

    /** 用户选中的选项标识，仅 {@link Status#ANSWERED} 且选的是候选项时非空。 */
    private final String optionId;

    /** 用户自己输入的答案原文，仅 {@link Status#ANSWERED} 且用户自行输入时非空。 */
    private final String text;

    /** 终态说明，供展示与回灌给模型，可为 {@code null}。 */
    private final String reason;

    /**
     * 构造答复。
     * <p>
     * 跨边界值类型只有这一个可见构造器；日常构造请用静态工厂。
     *
     * @param status   终态，不可为 {@code null}
     * @param optionId 选中的选项标识，可为 {@code null}
     * @param text     用户自己输入的答案原文，可为 {@code null}
     * @param reason   终态说明，可为 {@code null}
     */
    public AskAnswer(Status status, String optionId, String text, String reason) {
        this.status = status;
        this.optionId = optionId;
        this.text = text;
        this.reason = reason;
    }

    /**
     * 构造「用户选中了某个候选项」的答复。
     *
     * @param optionId 选项标识，不可为空白
     * @return 答复，保证非 {@code null}
     */
    public static AskAnswer answered(String optionId) {
        return new AskAnswer(Status.ANSWERED, optionId, null, null);
    }

    /**
     * 构造「用户自己输入了答案」的答复。
     *
     * @param text 用户输入的答案原文，不可为空白
     * @return 答复，保证非 {@code null}
     */
    public static AskAnswer custom(String text) {
        return new AskAnswer(Status.ANSWERED, null, text, null);
    }

    /**
     * 构造「用户放弃作答」的答复。
     *
     * @param reason 说明，可为 {@code null}
     * @return 答复，保证非 {@code null}
     */
    public static AskAnswer cancelled(String reason) {
        return new AskAnswer(Status.CANCELLED, null, null, reason);
    }

    /**
     * 构造「等待超时」的答复。
     *
     * @param reason 说明，可为 {@code null}
     * @return 答复，保证非 {@code null}
     */
    public static AskAnswer timedOut(String reason) {
        return new AskAnswer(Status.TIMED_OUT, null, null, reason);
    }

    /**
     * 构造「此处无法提问」的答复。
     *
     * @param reason 说明，不可为空白
     * @return 答复，保证非 {@code null}
     */
    public static AskAnswer unavailable(String reason) {
        return new AskAnswer(Status.UNAVAILABLE, null, null, reason);
    }

    /**
     * 获取答复终态。
     *
     * @return 终态，保证非 {@code null}
     */
    public Status getStatus() {
        return status;
    }

    /**
     * 获取用户选中的选项标识。
     *
     * @return 选项标识；用户自行输入、放弃、超时或不可用时为 {@code null}
     */
    public String getOptionId() {
        return optionId;
    }

    /**
     * 获取用户自己输入的答案原文。
     *
     * @return 答案原文；其余情形为 {@code null}
     */
    public String getText() {
        return text;
    }

    /**
     * 获取终态说明。
     *
     * @return 说明，可能为 {@code null}
     */
    public String getReason() {
        return reason;
    }

    /**
     * 判断是否拿到了可用答案（候选或自定义输入）。
     *
     * @return 拿到答案返回 {@code true}
     */
    public boolean isAnswered() {
        return status == Status.ANSWERED;
    }

    @Override
    public String toString() {
        return "AskAnswer{status=" + status + ", optionId=" + optionId + '}';
    }

    /**
     * 答复终态。
     * <p>
     * <b>为什么「无人可问」与「超时」要分开</b>：前者是外壳能力问题（{@code -cli} 没有界面），
     * 后者是这一次没等到人。前者重试不会有用，后者换个时机问可能就有答案——提问方据此决定
     * 要不要换个问法，而不是一律当成失败。
     */
    public enum Status {

        /** 拿到了答案：候选或用户自行输入，看 {@code optionId} / {@code text} 哪个非空。 */
        ANSWERED,

        /** 用户主动放弃作答（界面上的取消键），回合应当继续。 */
        CANCELLED,

        /** 等待超时。 */
        TIMED_OUT,

        /** 此处无法提问：没有交互外壳，或本上下文不允许提问（如子代理回合）。 */
        UNAVAILABLE
    }
}
