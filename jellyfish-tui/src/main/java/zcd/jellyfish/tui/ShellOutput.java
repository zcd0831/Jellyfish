package zcd.jellyfish.tui;

import zcd.jellyfish.tui.text.DisplayWidth;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.ArrayList;
import java.util.List;

/**
 * 命令输出面板：显示<b>最近一次</b>命令结果，可滚动，{@code Esc} 关闭。
 * <p>
 * <b>为什么命令输出不进消息区了</b>：消息区是「会话的投影」，而命令输出不是会话消息
 * （见 {@link ShellNotice} 的注释）。此前它按时间戳插进消息流，带来两个后果：首页上它会与字标、
 * 引导提示一起参与垂直居中，每贴一条输出就把字标顶上去一截；而消息区一旦内容超屏，
 * 命令输出就会被后来的对话顶走、却看不出「它去哪了」。改成常驻面板之后，命令输出有固定归属，
 * 会话投影也不必再为「外壳状态」留插入点。
 * <p>
 * <b>为什么只留最近一次</b>：命令输出是「对刚敲的那一下的反馈」，不是需要回溯的历史。
 * 留多次的话，连续敲几条命令就会在屏幕上堆成一摞互相无关的文本，用户真正想看的那一条反而被埋在中间。
 * 需要回看时命令本来就是可重放的（{@code /help} 再敲一次）。
 * <p>
 * <b>为什么不复用 {@link ShellNotice} 的缓冲</b>：那个缓冲是「按时间戳插进消息流」用的，
 * 现在只有插件通知走那条路（插件通知是异步推送的状态，不是用户敲出来的反馈）。两者生命周期、
 * 条数口径、淘汰规则都不同，共用一份状态只会让每处判断都要先问「这是哪一类」。
 * <p>
 * <b>线程契约</b>：本类只由渲染线程读写（与 {@link ChatState} 同一线程契约）。滚动偏移与视口行数
 * 都在渲染帧里更新，因此翻页的幅度永远与上一次真正画出来的高度一致。
 *
 * @author zcd
 */
final class ShellOutput {

    /**
     * 面板内容行的显示上限（不含边框）。
     * <p>
     * 直接取插件面板的同一上限（{@code ChatLayout.PANEL_MAX_ROWS}）：两者共用 {@code DOCK} 这个槽位，
     * 高度口径不同的话，「命令输出把插件面板顶掉」时整版会跟着跳一下。写成引用而不是各写一个数字，
     * 是为了让它们没有分头漂移的机会。
     * <p>
     * 超出的部分靠滚动看，而不是靠面板变高——面板高度由内容长度决定的话，
     * 一条 {@code /help} 就能把消息区挤没。
     */
    static final int MAX_CONTENT_ROWS = ChatLayout.PANEL_MAX_ROWS;

    /** 标题里命令原文字段的显示宽度上限，超出截断：标题是索引，不是内容。 */
    private static final int MAX_TITLE_COMMAND_WIDTH = 24;

    /** 标题里的关闭提示。 */
    private static final String CLOSE_HINT = "Esc 关闭";

    /** 触发本面板的命令原文，可为 {@code null}（无命令可回显的反馈）。 */
    private String command;

    /** 面板正文，保证非 {@code null}（不可见时不参与渲染）。 */
    private String text = "";

    /** 正文语义，决定前缀与颜色。 */
    private ShellNotice.Kind kind = ShellNotice.Kind.INFO;

    /** 是否可见：为 {@code false} 时面板不占任何行。 */
    private boolean visible;

    /** 顶部可见的视觉行下标。 */
    private int offset;

    /** 上一次渲染时的内容行数（滚动一页的幅度由此得出）。 */
    private int viewportRows;

    /** 上一次渲染时的总行数，仅用于标题里的范围提示。 */
    private int totalRows;

    /**
     * 显示一条命令的结果，替换掉上一条；
     * 文本为空白时等价于 {@link #close()}（没有可读内容的空面板没有意义）。
     *
     * @param command 命令原文，可为 {@code null} 或空白（标题用）
     * @param text    结果文本，可为 {@code null}
     * @param kind    结果语义，不可为 {@code null}
     */
    void show(String command, String text, ShellNotice.Kind kind) {
        if (text == null || text.trim().isEmpty()) {
            close();
            return;
        }
        this.command = command;
        this.text = text;
        this.kind = kind == null ? ShellNotice.Kind.INFO : kind;
        this.visible = true;
        // 从顶部开始读：命令输出（/help 的清单、/session 的列表）的第一行通常就是标题
        this.offset = 0;
    }

    /**
     * 关闭面板（内容一并丢弃）。
     * <p>
     * 内容必须一起丢：留着它只会让「下次打开时先看到上一次的输出」成为可能，
     * 而这正是本类要消除的困惑。
     */
    void close() {
        visible = false;
        command = null;
        text = "";
        offset = 0;
        viewportRows = 0;
        totalRows = 0;
    }

    /**
     * 判断面板是否可见。
     *
     * @return 可见返回 {@code true}
     */
    boolean isVisible() {
        return visible;
    }

    /**
     * 计算面板想要占用的总行数（含边框）。
     * <p>
     * 与 {@code ChatLayout.panelRows} 同一口径：<b>由内容决定但有上限</b>，
     * 高度账本据此分配——内容再长也不会把消息区挤没。
     *
     * @param terminalWidth 终端总列数
     * @return 总行数（含边框）；不可见时为 0
     */
    int desiredPanelRows(int terminalWidth) {
        if (!visible) {
            return 0;
        }
        int content = Math.min(lines(contentWidth(terminalWidth)).size(), MAX_CONTENT_ROWS);
        return Math.max(1, content) + ChatShell.BORDER_SIZE * 2;
    }

    /**
     * 渲染面板：取当前滚动位置对应的那一窗内容。
     * <p>
     * <b>切片放在这里而不是账本里</b>：账本只知道面板占几行，不知道面板里有什么；
     * 而「哪几行该出现」需要总行数与滚动偏移，这两样都是本类的状态。
     *
     * @param terminalWidth 终端总列数
     * @param contentRows   本帧可用的内容行数（不含边框）
     * @return 面板，保证非 {@code null}
     */
    DockPanel render(int terminalWidth, int contentRows) {
        if (!visible) {
            return DockPanel.none();
        }
        List<VisualLine> all = lines(contentWidth(terminalWidth));
        int rows = Math.max(1, contentRows);
        totalRows = all.size();
        viewportRows = rows;
        int maxOffset = Math.max(0, totalRows - rows);
        // 内容换了（更短）或终端变高之后，旧的偏移可能已经越界：每帧都夹一次，
        // 滚动状态因此不需要在「内容变化」的每个入口各自维护
        offset = Math.max(0, Math.min(offset, maxOffset));
        int to = Math.min(totalRows, offset + rows);
        List<VisualLine> window = new ArrayList<VisualLine>(all.subList(offset, to));
        return new DockPanel(title(), window);
    }

    /**
     * 按行滚动（正数向下，负数向上）。
     *
     * @param rows 行数，为 0 时不做任何事
     */
    void scrollBy(int rows) {
        if (!visible || rows == 0) {
            return;
        }
        int maxOffset = Math.max(0, totalRows - Math.max(1, viewportRows));
        offset = Math.max(0, Math.min(offset + rows, maxOffset));
    }

    /**
     * 向上翻一页。
     */
    void pageUp() {
        scrollBy(-pageStep());
    }

    /**
     * 向下翻一页。
     */
    void pageDown() {
        scrollBy(pageStep());
    }

    /**
     * 滚到内容末尾。
     */
    void toBottom() {
        if (!visible) {
            return;
        }
        offset = Math.max(0, totalRows - Math.max(1, viewportRows));
    }

    /**
     * 计算一次翻页的行数。
     * <p>
     * 留一行重叠（与 {@link ChatState#pageUp()} 同口径）：翻页后有一个熟悉的行留在屏幕上，
     * 读者才能确认自己接上了上文。
     *
     * @return 行数，至少为 1
     */
    private int pageStep() {
        return Math.max(1, viewportRows - 1);
    }

    /**
     * 判断当前视野上方是否还有未显示的行。
     *
     * @return 有返回 {@code true}
     */
    boolean hasAbove() {
        return offset > 0;
    }

    /**
     * 判断当前视野下方是否还有未显示的行。
     *
     * @return 有返回 {@code true}
     */
    boolean hasBelow() {
        return offset + Math.max(1, viewportRows) < totalRows;
    }

    /**
     * 把正文折成视觉行。
     *
     * @param contentWidth 内容列数（不含边框）
     * @return 视觉行列表，保证非 {@code null}
     */
    private List<VisualLine> lines(int contentWidth) {
        return TranscriptProjector.noticeBody(text, kind, contentWidth);
    }

    /**
     * 生成面板标题。
     * <p>
     * 三段：命令原文（索引作用）、滚动范围（仅在内容超出视野时出现）、关闭键位。
     * 键位提示必须常驻——面板没有键位提示时，{@code Esc} 这个能力只能靠读文档才知道。
     *
     * @return 标题文本，保证非 {@code null}
     */
    private String title() {
        StringBuilder title = new StringBuilder(" ");
        if (command != null && !command.trim().isEmpty()) {
            title.append(abbreviate(command.trim()));
        }
        if (hasAbove() || hasBelow()) {
            if (title.length() > 1) {
                title.append(" \u00b7 ");
            }
            title.append(offset + 1).append('-')
                    .append(Math.min(totalRows, offset + Math.max(1, viewportRows)))
                    .append('/').append(totalRows);
        }
        if (title.length() > 1) {
            title.append(" \u00b7 ");
        }
        title.append(CLOSE_HINT).append(' ');
        return title.toString();
    }

    /**
     * 按显示宽度截断标题里的命令原文。
     *
     * @param text 命令原文，不可为 {@code null}
     * @return 截断后的文本，保证非 {@code null}
     */
    private static String abbreviate(String text) {
        if (DisplayWidth.of(text) <= MAX_TITLE_COMMAND_WIDTH) {
            return text;
        }
        int codePoints = text.codePointCount(0, text.length());
        for (int limit = codePoints - 1; limit > 0; limit--) {
            String candidate = text.substring(0, text.offsetByCodePoints(0, limit)) + "\u2026";
            if (DisplayWidth.of(candidate) <= MAX_TITLE_COMMAND_WIDTH) {
                return candidate;
            }
        }
        return "\u2026";
    }

    /**
     * 计算面板内容列数（不含边框）。
     *
     * @param terminalWidth 终端总列数
     * @return 内容列数，最小为 1
     */
    private static int contentWidth(int terminalWidth) {
        return Math.max(1, terminalWidth - ChatShell.BORDER_SIZE * 2);
    }
}
