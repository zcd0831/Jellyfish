package zcd.jellyfish.tui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 外壳自有命令 {@code /mouse}：在运行期把鼠标交还终端或收回应用（键位 {@code Ctrl+O}）。
 * <p>
 * <b>为什么需要它</b>：滚轮事件要求应用<b>捕获</b>鼠标（见 {@link TuiApp#configure()}），
 * 而捕获一旦开启，终端的鼠标选择就被应用截走——想拖选复制必须靠终端自己的「按住修饰键强制选择」，
 * 而这项能力各终端差别很大（macOS 的 Terminal.app 上 Option 拖动是矩形选择而不是普通选择，
 * 实际等于没有）。命令给出第二条路：把鼠标交还终端，拖选与 {@code ⌘C} 立刻恢复可用，代价只是滚轮停用；
 * 剪完再收回即可，不必重启，也不必牺牲默认行为。
 * <p>
 * <b>为什么归外壳而不是内核命令注册表</b>：鼠标捕获是终端能力，{@code -cli} / {@code -server} 里根本不存在，
 * 注册进内核只会让 {@code /help} 多出一条对它们毫无意义的条目。与 {@code /exit} / {@code /ui} 同一口径。
 * <p>
 * <b>为什么是纯函数</b>：取值解析与文案都只依赖「当前是不是捕获态」这个布尔量，
 * 判定与文案因此能在没有终端的情况下被断言；真正改终端模式的调用留在 {@link TuiApp}，
 * 与 {@link InputKeyMapper} / {@link MouseScrollMapper} 的分工完全一致。
 * <p>
 * <b>文案是契约</b>：关闭态下滚轮确实不工作，提示必须同时说清「现在能复制了」与「滚动改用哪些键」，
 * 否则用户会把「滚轮没反应」当成故障。
 *
 * @author zcd
 */
final class MouseCommand {

    /** 命令名（不含前缀），供外壳补全清单使用。 */
    static final String NAME = "mouse";

    /** 命令前缀。 */
    static final String PREFIX = "/" + NAME;

    /** 收回捕获（滚轮恢复）的取值。 */
    private static final String ON = "on";

    /** 交还终端（原生选择恢复）的取值。 */
    private static final String OFF = "off";

    /** 用法说明：解析失败时贴给用户，同时也是唯一的口径来源。 */
    private static final String USAGE = "用法：" + PREFIX + " [" + ON + " | " + OFF + "]（无参数为切换）";

    /** 交还终端后的提示。 */
    private static final String OFF_NOTICE = "鼠标已交还终端：直接拖选即可复制，再按 ⌘C / Ctrl+Shift+C 取走"
            + "；滚轮停用，消息区滚动改用 PageUp / PageDown / End。\n"
            + "复制完按 Ctrl+O 或 " + PREFIX + " 收回鼠标。";

    /** 收回应用后的提示。 */
    private static final String ON_NOTICE = "鼠标已交回应用：滚轮可用"
            + "；终端本地选中需按住修饰键（macOS 为 Option）。\n"
            + "要复制屏幕文本时按 Ctrl+O 或 " + PREFIX + " 交还终端。";

    /**
     * 交还终端期间的状态栏标记。
     * <p>
     * 前缀三个空格与 {@link StatusBarView} 里片段之间的分隔符同形：标记看起来是「又一段补充说明」，
     * 而不是内核字段的一部分。
     */
    private static final String OFF_MARKER = "   鼠标:交还终端（Ctrl+O 收回）";

    private MouseCommand() {
    }

    /**
     * 判断输入是否为 {@code /mouse}。
     * <p>
     * 只比对首词，因此 {@code /mouse off} 也命中——取值由 {@link #targetOf} 继续解析。
     *
     * @param input 用户输入，可为 {@code null}
     * @return 是 {@code /mouse} 返回 {@code true}
     */
    static boolean isMouse(String input) {
        if (input == null) {
            return false;
        }
        String trimmed = input.trim();
        int space = trimmed.indexOf(' ');
        String head = space < 0 ? trimmed : trimmed.substring(0, space);
        return PREFIX.equals(head);
    }

    /**
     * 解析出本次要切换到的捕获状态。
     * <p>
     * 无参数时取反（{@link InputAction#TOGGLE_MOUSE} 与 {@code /mouse} 因此是同一件事的两个入口）；
     * 带取值时以取值为准，已在目标态上时调用方不必重复改终端。
     *
     * @param input    命令原文，可为 {@code null}
     * @param captured 当前是否处于捕获态
     * @return 目标状态；取值非法或参数过多时返回 {@link Optional#empty()}
     */
    static Optional<Boolean> targetOf(String input, boolean captured) {
        List<String> args = argsOf(input);
        if (args.isEmpty()) {
            return Optional.of(!captured);
        }
        if (args.size() > 1) {
            return Optional.empty();
        }
        String argument = args.get(0).toLowerCase(Locale.ROOT);
        if (ON.equals(argument)) {
            return Optional.of(Boolean.TRUE);
        }
        if (OFF.equals(argument)) {
            return Optional.of(Boolean.FALSE);
        }
        return Optional.empty();
    }

    /**
     * 取切换后的提示文本。
     *
     * @param captured 切换后的状态（{@code true} 为已收回捕获）
     * @return 提示文本，保证非 {@code null}
     */
    static String notice(boolean captured) {
        return captured ? ON_NOTICE : OFF_NOTICE;
    }

    /**
     * 取状态栏上的捕获态标记。
     * <p>
     * 交还终端是一个「持续有效」的状态：滚轮看起来没反应时，屏幕上必须有一处说明原因以及怎么收回。
     * 捕获态不占位（那是默认状态，也是滚轮可用、无需解释的状态）。
     *
     * @param captured 当前是否处于捕获态
     * @return 标记文本；捕获态下返回空串
     */
    static String statusMarker(boolean captured) {
        return captured ? "" : OFF_MARKER;
    }

    /**
     * 取用法错误文本。
     *
     * @return 用法说明，保证非 {@code null}
     */
    static String usageError() {
        return USAGE;
    }

    /**
     * 把输入切成参数（去掉命令本身）。
     * <p>
     * 与 {@code UiCommand} 同一写法：空白按 {@code \s+} 处理，因此 {@code /mouse   off} 也能解析。
     *
     * @param input 命令原文，可为 {@code null}
     * @return 参数列表，保证非 {@code null}
     */
    private static List<String> argsOf(String input) {
        if (input == null) {
            return new ArrayList<String>();
        }
        String[] parts = input.trim().split("\\s+");
        List<String> args = new ArrayList<String>(Arrays.asList(parts));
        return args.size() <= 1 ? new ArrayList<String>() : new ArrayList<String>(args.subList(1, args.size()));
    }
}
