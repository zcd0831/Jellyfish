package zcd.jellyfish.core.conversation;

/**
 * 一次提交的处理策略：外壳的「能力」与「UX 事实」，由外壳声明，内核只执行。
 * <p>
 * <b>为什么策略是入参而不是内核推断</b>：三个外壳对同一条输入的行为差异是<b>真实存在</b>的，
 * 而且差异的根据各不相同——
 * <ul>
 *     <li><b>命令</b>：Server 把命令拆成独立端点（{@code POST /commands}），因此 {@code /chat} 里的
 *     {@code /help} 依然是普通文本；TUI / CLI 则在本轮分流；</li>
 *     <li><b>输入指令</b>：只有 TUI 注册了指令处理器并解析（CLI 明确不启用；Server 保持现状不解析）；</li>
 *     <li><b>建会话</b>：TUI 从首页进入、真正要用时才建；CLI 启动期已建；Server 按 id 寻址、必须已存在。</li>
 * </ul>
 * 把这些差异写成内核里的 {@code if (shell == ...)} 会把「外壳种类」变成内核的一个分支维度，
 * 而它本来只是调用方的一种选择。反过来把差异全抹平（三处行为一致）则会改变 Server 与 CLI 的既有契约。
 * <p>
 * <b>为什么不是一堆布尔参数的裸方法</b>：三个开关的组合有语义（例如「不建会话又要求会话」就是必然拒绝），
 * 具名工厂把这几种真实存在的组合固定下来，调用点读起来就是一句声明而不是一串 {@code true, false, ...}。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class SubmissionPolicy {

    /** 无会话时怎么办。 */
    public enum SessionPolicy {

        /** 按需创建（用 {@code SessionDefaults} 里那组待生效默认值），并切换为当前会话。 */
        CREATE_IF_NEEDED,

        /** 必须有会话，否则 {@link Submission.RejectReason#NO_SESSION}。 */
        REQUIRE_EXISTING
    }

    /** 是否把「看起来像命令」的原文交给命令域执行。 */
    private final boolean commands;

    /** 是否解析输入指令（{@code !} / {@code @}）。 */
    private final boolean directives;

    /** 无会话时的处置。 */
    private final SessionPolicy sessions;

    /**
     * 构造策略。
     *
     * @param commands   是否执行命令
     * @param directives 是否解析输入指令
     * @param sessions   无会话时的处置，不可为 {@code null}
     */
    private SubmissionPolicy(boolean commands, boolean directives, SessionPolicy sessions) {
        this.commands = commands;
        this.directives = directives;
        this.sessions = sessions;
    }

    /**
     * 构造任意组合，供测试与将来可能的新外壳使用。
     *
     * @param commands   是否执行命令
     * @param directives 是否解析输入指令
     * @param sessions   无会话时的处置，不可为 {@code null}
     * @return 策略，保证非 {@code null}
     */
    public static SubmissionPolicy of(boolean commands, boolean directives, SessionPolicy sessions) {
        if (sessions == null) {
            throw new zcd.jellyfish.api.JellyfishException("session policy must not be null");
        }
        return new SubmissionPolicy(commands, directives, sessions);
    }

    /**
     * TUI：命令 + 输入指令 + 首页按需建会话。
     *
     * @return 策略，保证非 {@code null}
     */
    public static SubmissionPolicy tui() {
        return new SubmissionPolicy(true, true, SessionPolicy.CREATE_IF_NEEDED);
    }

    /**
     * CLI：命令执行、<b>不解析输入指令</b>（决策 D6：保持既有行为）、必须有会话。
     *
     * @return 策略，保证非 {@code null}
     */
    public static SubmissionPolicy cli() {
        return new SubmissionPolicy(true, false, SessionPolicy.REQUIRE_EXISTING);
    }

    /**
     * Server {@code POST /sessions/{id}/chat}：只有对话。
     * <p>
     * 不执行命令、不解析输入指令——命令只走 {@code POST /sessions/{id}/commands}
     * （决策 D5：对话与命令的 API 保持分开），因此本策略下 {@code /help} 与 {@code !ls}
     * 与改造前一样，是发给模型的普通文本。
     *
     * @return 策略，保证非 {@code null}
     */
    public static SubmissionPolicy serverChat() {
        return new SubmissionPolicy(false, false, SessionPolicy.REQUIRE_EXISTING);
    }

    /**
     * 判断是否执行命令。
     *
     * @return 执行返回 {@code true}
     */
    public boolean isCommands() {
        return commands;
    }

    /**
     * 判断是否解析输入指令。
     *
     * @return 解析返回 {@code true}
     */
    public boolean isDirectives() {
        return directives;
    }

    /**
     * 获取无会话时的处置。
     *
     * @return 处置，保证非 {@code null}
     */
    public SessionPolicy getSessions() {
        return sessions;
    }

    @Override
    public String toString() {
        return "SubmissionPolicy{commands=" + commands + ", directives=" + directives
                + ", sessions=" + sessions + '}';
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SubmissionPolicy)) {
            return false;
        }
        SubmissionPolicy that = (SubmissionPolicy) other;
        return commands == that.commands && directives == that.directives && sessions == that.sessions;
    }

    @Override
    public int hashCode() {
        int result = commands ? 1 : 0;
        result = 31 * result + (directives ? 1 : 0);
        result = 31 * result + sessions.hashCode();
        return result;
    }
}
