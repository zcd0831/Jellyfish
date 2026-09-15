package zcd.jellyfish.tui;

/**
 * TUI 用法说明：作为外壳自有的一段说明，追加在 {@code /help} 输出之后。
 * <p>
 * <b>为什么放在外壳侧、由 {@link TuiApp} 追加而不是写进内核 {@code CommandManager.renderHelp()}</b>：
 * 这里的内容全是 TUI 专属的（{@code Ctrl+S} 发送、{@code Esc} 中断、滚轮滚动），
 * 写进内核会让 {@code -cli} / {@code -server} 的 {@code /help} 冒出按不了的键位。
 * 外壳自有内容由外壳追加，与 {@code /exit}、{@code /ui} 不进内核命令注册表同一条口径。
 * <p>
 * <b>为什么不再在启动时自动显示</b>：TUI 现在从<b>首页</b>（无会话）进入，首页只显示标识；
 * 用户主动敲 {@code /help} 时才需要这份说明。这样首页干净，说明也不会随会话历史反复出现。
 * <p>
 * <b>文案是契约</b>：键位取自 {@link InputKeyMapper} 的判定，命令入口取自 {@link ShellCommand}
 * 与命令域，二者一旦改动，这里必须同步——所以文案集中在本类，不散落在各处。
 *
 * @author zcd
 */
public final class ShellUsage {

    /**
     * 用法说明正文。
     * <p>
     * 键位采用 T5 的反转方案：{@code Ctrl+S} 发送、{@code Enter} 换行（见 {@link InputKeyMapper}）。
     */
    private static final String TEXT = "TUI 用法（外壳自有）：\n"
            + "  Ctrl+S 发送 · Enter 换行 · Esc 中断 · Ctrl+C 退出\n"
            + "  输入 / 唤起命令补全 · /ui 管理插件面板\n"
            + "  PageUp / PageDown 或滚轮滚动消息区";

    private ShellUsage() {
    }

    /**
     * 获取用法说明正文。
     *
     * @return 用法说明正文，保证非 {@code null}
     */
    public static String text() {
        return TEXT;
    }
}
