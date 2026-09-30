package zcd.jellyfish.api;

import java.util.Objects;

/**
 * 运行时信息只读快照：插件能看到的「我正跑在哪种外壳里」，以及外壳能如实回答的几个进程级事实。
 * <p>
 * <b>为什么需要它</b>：插件此前连「我在哪种外壳里、能不能弹框」都感知不到，于是无法优雅降级——
 * 一个只在 TUI 有意义的界面贡献，在 {@code -cli} 下也会被收集与渲染（只是没人看得见）。
 * <p>
 * <b>边界没有松动</b>：这里的四个字段都是<b>进程级事实</b>，不含会话状态、不含工作目录、不含请求内容。
 * 「插件碰不到会话、也拿不到 cwd」这条纪律不因为本类型而改变；快照里刻意<b>没有</b>
 * {@code sessionId}、{@code agentId}、{@code cwd}、{@code contextUsage}、{@code systemPrompt}
 * 与模型注册表。
 * <p>
 * <b>{@link #supportsApproval()} 是静态语义，不是「此刻有人在看」</b>：它回答的是
 * 「本外壳<b>具备</b>审批通道吗」。字段名不叫 {@code hasApprover} 就是为了让
 * 「现在有人在线吗」这种误读在编译期就不可能发生——那是本类型无法可靠回答的问题
 * （以 HTTP 外壳为例：客户端可以先提交审批而不订阅事件流，也可能流断了但页面还开着）。
 * <p>
 * 插件应当把它用于<b>降级决策</b>（「没有审批通道时干脆不要提供需要写权限的动作」），
 * 而不是用来判断一次调用会不会被批准：安全边界在审批通道自身的 fail-closed，
 * 不在这个字段。因此两个方向猜错的代价都可接受——猜 {@code true} 而实际无人，会走审批被拒；
 * 猜 {@code false} 而实际有人，只是少提供一个功能。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class RuntimeInfo {

    /** 外壳种类。 */
    public enum Shell {
        /** 单次调用、不交互的命令行外壳。 */
        CLI,
        /** 交互式终端界面外壳。 */
        TUI,
        /** HTTP 服务外壳。 */
        SERVER
    }

    /** 单例：未知外壳。 */
    private static final RuntimeInfo UNKNOWN = new RuntimeInfo(Shell.CLI, false, false, false);

    /** 外壳种类。 */
    private final Shell shell;

    /** 是否有可交互界面。 */
    private final boolean hasUI;

    /** 是否具备审批通道。 */
    private final boolean supportsApproval;

    /** 是否有终端。 */
    private final boolean interactive;

    /**
     * 构造运行时信息快照。
     * <p>
     * 由内核构造。一般不需要直接用它——按外壳取 {@link #cli(boolean)} / {@link #tui(boolean)} /
     * {@link #server(boolean)} 更不容易写错三个布尔值的组合。
     *
     * @param shell           外壳种类，不可为 {@code null}
     * @param hasUI           是否有可交互界面
     * @param supportsApproval 是否具备审批通道
     * @param interactive     是否有终端
     */
    public RuntimeInfo(Shell shell, boolean hasUI, boolean supportsApproval, boolean interactive) {
        this.shell = shell == null ? Shell.CLI : shell;
        this.hasUI = hasUI;
        this.supportsApproval = supportsApproval;
        this.interactive = interactive;
    }

    /**
     * 构造命令行外壳的快照。
     * <p>
     * 它总是没有可交互界面、也总是没有审批通道：那里没有审批者，
     * 因此「需要审批」的调用一律按拒绝处理。
     *
     * @param interactive 是否有终端（输出被管道接走时为 {@code false}）
     * @return 快照
     */
    public static RuntimeInfo cli(boolean interactive) {
        return new RuntimeInfo(Shell.CLI, false, false, interactive);
    }

    /**
     * 构造交互式终端界面外壳的快照。
     *
     * @param interactive 是否有终端
     * @return 快照
     */
    public static RuntimeInfo tui(boolean interactive) {
        return new RuntimeInfo(Shell.TUI, true, true, interactive);
    }

    /**
     * 构造 HTTP 服务外壳的快照。
     * <p>
     * 没有可交互界面，但<b>具备</b>审批通道（审批走 HTTP 桥），因此
     * {@link #supportsApproval()} 为 {@code true}——注意它不表示此刻有客户端连着。
     *
     * @param interactive 是否有终端（服务化运行时通常为 {@code false}）
     * @return 快照
     */
    public static RuntimeInfo server(boolean interactive) {
        return new RuntimeInfo(Shell.SERVER, false, true, interactive);
    }

    /**
     * 构造「未知外壳」的快照：外壳为 {@link Shell#CLI}，其余全为 {@code false}。
     * <p>
     * 供不经过装配根的用法使用（单元测试与嵌入式集成）——它们不经过外壳启动流程，
     * 拿不到真实值。给一个保守的缺省值比让调用方被迫伪造一个外壳要好。
     *
     * @return 快照
     */
    public static RuntimeInfo unknown() {
        return UNKNOWN;
    }

    /**
     * 按外壳种类构造快照。
     * <p>
     * 把「外壳 → 有无界面 / 有无审批通道」这份映射留在本类里，装配根只传一个枚举，
     * 不必自己维护一份会与三个专有工厂分叉的 {@code switch}。
     *
     * @param shell       外壳种类，可为 {@code null}（按 {@link Shell#CLI} 处理）
     * @param interactive 是否有终端
     * @return 快照
     */
    public static RuntimeInfo forShell(Shell shell, boolean interactive) {
        if (shell == Shell.TUI) {
            return tui(interactive);
        }
        if (shell == Shell.SERVER) {
            return server(interactive);
        }
        return cli(interactive);
    }

    /**
     * 获取外壳种类。
     *
     * @return 外壳种类，保证非 {@code null}
     */
    public Shell getShell() {
        return shell;
    }

    /**
     * 判断是否有可交互界面。
     *
     * @return 有可交互界面返回 {@code true}
     */
    public boolean hasUI() {
        return hasUI;
    }

    /**
     * 判断是否具备审批通道。
     * <p>
     * 静态语义，不表示此刻有审批者在线（见类注释）。
     *
     * @return 具备审批通道返回 {@code true}
     */
    public boolean supportsApproval() {
        return supportsApproval;
    }

    /**
     * 判断是否有终端。
     *
     * @return 有终端返回 {@code true}
     */
    public boolean isInteractive() {
        return interactive;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RuntimeInfo)) {
            return false;
        }
        RuntimeInfo that = (RuntimeInfo) other;
        return hasUI == that.hasUI
                && supportsApproval == that.supportsApproval
                && interactive == that.interactive
                && shell == that.shell;
    }

    @Override
    public int hashCode() {
        return Objects.hash(shell, hasUI, supportsApproval, interactive);
    }

    @Override
    public String toString() {
        return "RuntimeInfo{shell=" + shell + ", hasUI=" + hasUI
                + ", supportsApproval=" + supportsApproval + ", interactive=" + interactive + '}';
    }
}
