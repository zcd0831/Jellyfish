package zcd.jellyfish.tui;

import dev.tamboui.style.Style;
import zcd.jellyfish.tui.text.CenteredLine;
import zcd.jellyfish.tui.text.DisplayWidth;
import zcd.jellyfish.tui.text.VisualLine;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页引导提示：字标下方的几行 dim 提示，回答「进来之后能干什么」。
 * <p>
 * <b>为什么需要它</b>：首页上没有会话、没有历史，用户看到的只有字标；而首页本身已经支持
 * 几条不需要会话的命令（见 {@code docs/constraints/shells.md} 的「分流与首页」）。不提示的话，
 * 这些能力只能靠读文档才知道。
 * <p>
 * <b>为什么只列入口、不复述键位</b>：键位已经在两处写着——输入框的 placeholder
 * （{@code Ctrl+S} 发送 / {@code Enter} 换行 / {@code Esc} 中断）与 {@code /help} 追加的
 * {@link ShellUsage}。首页再抄一份，就要维护第三处，而它离屏幕最近、最容易被忘记。
 * <p>
 * <b>放不下就整行丢弃，不裁切</b>：与 {@code StatusBarView.appendFragments} 同一口径——
 * 被切掉一半的提示既读不懂，又会让人以为界面坏了。窄终端上提示消失、只留字标，
 * 比留下一行残句干净。
 * <p>
 * <b>文案是契约</b>：这里出现的命令名必须在命令域里真的存在且在首页可用
 * （{@code sessionRequired=false}），一旦命令改动，这里要同步。
 * <p>
 * 纯函数，不读终端、不改状态，可单测。
 *
 * @author zcd
 */
public final class HomeHints {

    /** 字标与提示之间的空行数。 */
    static final int LEADING_BLANK_ROWS = 1;

    /** 提示正文：{@code /help} 与 {@code /resume} 都是首页可直接执行的命令。 */
    static final String[] HINTS = {
            "/help 查看命令 \u00b7 /resume 继续上次会话",
            "! 执行 shell 命令 \u00b7 @ 引用文件 \u00b7 / 唤起命令补全"};

    /** 提示样式：dim 压一档，让它明显弱于字标，不与内容抢注意力。 */
    private static final Style HINT_STYLE = Style.EMPTY.dim();

    private HomeHints() {
    }

    /**
     * 生成首页提示行。
     * <p>
     * 返回列表<b>可能为空</b>（终端太窄，一行都放不下），因此调用方不能假设它至少有一行——
     * 首页的内容本来就允许只剩字标。
     *
     * @param width 消息区可用列数，小于 1 时按 1 处理
     * @return 视觉行列表（有可见提示时首行是空行），保证非 {@code null}
     */
    public static List<VisualLine> lines(int width) {
        int available = Math.max(1, width);
        List<VisualLine> out = new ArrayList<VisualLine>(HINTS.length + LEADING_BLANK_ROWS);
        List<VisualLine> visible = new ArrayList<VisualLine>(HINTS.length);
        for (String hint : HINTS) {
            if (DisplayWidth.of(hint) <= available) {
                visible.add(CenteredLine.of(hint, HINT_STYLE, available));
            }
        }
        if (visible.isEmpty()) {
            return out;
        }
        out.add(VisualLine.EMPTY);
        out.addAll(visible);
        return out;
    }
}
