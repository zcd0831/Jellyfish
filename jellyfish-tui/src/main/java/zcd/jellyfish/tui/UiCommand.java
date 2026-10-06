package zcd.jellyfish.tui;

import zcd.jellyfish.api.extension.CommandChoice;
import zcd.jellyfish.api.ui.UiRegion;
import zcd.jellyfish.infra.ui.OwnedPanel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外壳自有命令 {@code /ui}：查看与切换插件的界面贡献。
 * <p>
 * <b>为什么它归外壳而不是核心命令注册表</b>：{@code /ui} 操作的是「区域」这个外壳概念——
 * CLI 与 server 没有区域，注册进内核只会让 {@code /help} 里多出一条对 {@code -cli} 毫无意义的条目。
 * 这与 {@code /exit} 是同一条口径（见 {@link ShellCommand}）。
 * <p>
 * <b>为什么必须有这个命令</b>：面板是独占型资源，一块区域同一时刻只显示一个；没有切换手段时，
 * 「两个插件都想要 {@code DOCK}」就变成了先注册者永久胜出，用户没有任何表达偏好的方式。
 * <p>
 * <b>为什么是纯函数 + 一个可变的状态对象</b>：解析与渲染是纯逻辑（可单测），
 * 而落位是外壳的视图状态（{@link UiPlacement}）。本类不做 I/O、不认识界面，
 * 因此它的输出就是一段要贴到屏幕上的文本。
 *
 * @author zcd
 */
final class UiCommand {

    /** 命令名（不含前缀），供外壳补全清单使用。 */
    static final String NAME = "ui";

    /** 命令前缀。 */
    static final String PREFIX = "/" + NAME;

    /** 区域名到区域的映射，同时定义清单的渲染顺序（面板区域在前，状态栏殿后）。 */
    private static final Map<String, UiRegion> REGIONS = regions();

    /** 关闭当前区域或状态栏片段的取值。 */
    private static final String OFF = "off";

    /** 恢复当前区域或状态栏片段的取值。 */
    private static final String ON = "on";

    /** 在区域内轮换到下一个候选的显式子命令。 */
    private static final String CYCLE = "cycle";

    /** 直接列出清单文本、不弹选择页的子命令。 */
    private static final String LIST = "list";

    /** 用法说明（清单与报错共用一处，避免两处口径不一致）。 */
    private static final String USAGE = "用法：" + PREFIX + " [" + LIST + "]"
            + " | [<region> [<pluginId> | " + CYCLE + " | " + OFF + " | " + ON + "]]"
            + "   <region>：" + String.join(" / ", REGIONS.keySet());

    private UiCommand() {
    }

    /**
     * 判断输入是否为 {@code /ui}。
     * <p>
     * 只比对首词，因此 {@code /ui dock} 也命中——它由 {@link #execute} 继续解析。
     *
     * @param input 用户输入，可为 {@code null}
     * @return 是 {@code /ui} 返回 {@code true}
     */
    static boolean isUi(String input) {
        if (input == null) {
            return false;
        }
        String trimmed = input.trim();
        int space = trimmed.indexOf(' ');
        String head = space < 0 ? trimmed : trimmed.substring(0, space);
        return PREFIX.equals(head);
    }

    /**
     * 执行一条 {@code /ui} 命令。
     * <p>
     * <b>两级页面</b>：无参数时给出区域候选（二级页），{@code /ui <region>} 给出该区域的
     * pluginId 与 on / off 候选（三级页）；这两条路都<b>只读</b>，不改动 {@link UiPlacement}，
     * 只有落到具体的 {@code <pluginId>} / {@code on} / {@code off} / {@code cycle} 才改动它。
     * <p>
     * <b>级联不依赖选择页支持嵌套</b>：外壳确认一条候选时是把取值拼回命令再执行一次
     * （{@code /ui} → {@code /ui dock} → {@code /ui dock <pluginId>}），因此「下一级」就是本方法被再调一次，
     * 选择页组件本身仍是单层的。
     * <p>
     * 清单文本挪到 {@code /ui list}：弹了选择页就贴不了文本，而未生效的插件快捷键说明是诊断行、
     * 塞不进候选里，因此需要保留一个纯文本入口。返回候选时那份文本仍作为 {@code output} 一并给出，
     * 好让选择页打不开时（候选为空）还有东西可显示。
     * <p>
     * 所有非法输入都返回错误结果而不是抛异常：用户敲错命令不该让外壳走异常路径。
     *
     * @param input     命令原文
     * @param placement 落位状态，会被就地修改
     * @param panels    最近一次收集到的面板，可为 {@code null}
     * @param shortcutCandidates 没生效的插件键位说明行，可为 {@code null}
     * @return 结果（文本、或要求挑一个取值的候选），保证非 {@code null}
     */
    static Result execute(String input, UiPlacement placement, List<OwnedPanel> panels,
                          List<String> shortcutCandidates) {
        List<String> args = argsOf(input);
        String list = renderList(placement, panels) + renderShortcuts(shortcutCandidates);
        if (args.isEmpty()) {
            return Result.choices(list, regionChoices(placement, panels));
        }
        String head = args.get(0);
        if (LIST.equalsIgnoreCase(head)) {
            return Result.ok(list);
        }
        UiRegion region = REGIONS.get(head.toLowerCase());
        if (region == null) {
            return Result.error("未知区域：" + head + "\n" + USAGE);
        }
        if (args.size() == 1) {
            return Result.choices(list, actionChoices(region, placement, panels));
        }
        String action = args.get(1);
        if (OFF.equalsIgnoreCase(action)) {
            placement.hide(region);
            return Result.ok(nameOf(region) + " 区域已关闭（" + PREFIX + " " + nameOf(region) + " " + ON + " 恢复）");
        }
        if (ON.equalsIgnoreCase(action)) {
            placement.show(region);
            return Result.ok(nameOf(region) + " 区域已恢复显示");
        }
        if (CYCLE.equalsIgnoreCase(action)) {
            return cycle(region, placement, panels);
        }
        return assign(region, action, placement, panels, shortcutCandidates);
    }

    /**
     * 构造区域候选：区域清单。
     * <p>
     * 取值就是区域名，因此确认后拼出的 {@code /ui <region>} 会再进 {@link #execute} 一次、
     * 进而打开三级页——「两级页面」正是靠这条命令级联成立的。
     * <p>
     * <b>两个调用点，同一处构造</b>：{@link #execute}（命令被真正执行时）与
     * {@link ShellCommand#options}（补全面板选中 {@code /ui}、命令还没执行但用户已经要看候选时）。
     * 后者是只读查询，因此这里刻意不碰 {@link UiPlacement} 的任何写方法——
     * 否则「选中命令名」就成了一次有副作用的操作。
     *
     * @param placement 落位状态，不可为 {@code null}
     * @param panels    面板候选，可为 {@code null}
     * @return 候选列表，保证非 {@code null}
     */
    static List<CommandChoice> regionChoices(UiPlacement placement, List<OwnedPanel> panels) {
        Map<UiRegion, List<OwnedPanel>> candidates = placement.candidates(panels);
        Map<UiRegion, OwnedPanel> selected = placement.selected(panels);
        List<CommandChoice> choices = new ArrayList<CommandChoice>(REGIONS.size());
        for (UiRegion region : REGIONS.values()) {
            String name = nameOf(region);
            choices.add(new CommandChoice(name, name,
                    briefOf(region, placement, candidates, selected), false));
        }
        return choices;
    }

    /**
     * 构造三级选择页的候选：某区域可选的 pluginId，外加 on / off。
     * <p>
     * 当前实际显示的那个候选标 {@code current}，选择页会打出「（当前）」——用户据此知道
     * 自己现在看的是谁的面板。状态栏是拼接型区域，没有可指定的插件，因此只给 on / off。
     *
     * @param region    区域
     * @param placement 落位状态
     * @param panels    面板候选，可为 {@code null}
     * @return 候选列表，保证非 {@code null}
     */
    private static List<CommandChoice> actionChoices(UiRegion region, UiPlacement placement,
                                                     List<OwnedPanel> panels) {
        List<CommandChoice> choices = new ArrayList<CommandChoice>();
        List<OwnedPanel> list = placement.candidates(panels).get(region);
        OwnedPanel current = placement.selected(panels).get(region);
        if (list != null) {
            for (OwnedPanel panel : list) {
                String owner = panel.getOwner();
                boolean isCurrent = current != null && owner.equals(current.getOwner());
                choices.add(new CommandChoice(owner, owner, titleOf(panel), isCurrent));
            }
        }
        choices.add(new CommandChoice(ON, ON, "恢复本区域显示", false));
        choices.add(new CommandChoice(OFF, OFF, "关闭本区域（只影响显示，不清候选）", false));
        return choices;
    }

    /**
     * 轮换一个区域的显示。
     * <p>
     * 入口是 {@code /ui <region> cycle}：{@code /ui <region>} 现在打开三级选择页，
     * 轮换因此需要一个显式的字——否则同一串输入要同时表示「弹出一个页面」和「立刻换一个」。
     *
     * @param region    区域
     * @param placement 落位状态
     * @param panels    面板候选
     * @return 结果
     */
    private static Result cycle(UiRegion region, UiPlacement placement, List<OwnedPanel> panels) {
        if (region == UiRegion.STATUS) {
            // 状态栏是拼接型：片段共存，没有「一块区域放一个」可轮换，这个子命令的语义就是「显示」
            placement.show(region);
            return Result.ok("状态栏片段已显示");
        }
        OwnedPanel next = placement.cycle(region, panels);
        if (next == null) {
            return Result.ok(nameOf(region) + " 区域没有插件面板");
        }
        return Result.ok(nameOf(region) + " 区域现在显示：" + next.getOwner());
    }

    /**
     * 把某个插件的面板钉到指定区域。
     *
     * @param region    区域
     * @param owner     插件标识
     * @param placement 落位状态
     * @param panels    面板候选
     * @return 结果
     */
    private static Result assign(UiRegion region, String owner, UiPlacement placement, List<OwnedPanel> panels,
                                 List<String> shortcutCandidates) {
        if (!UiPlacement.isPanelRegion(region)) {
            return Result.error("状态栏是拼接型区域，不能指定插件；只能 " + OFF + " / " + ON + "\n" + USAGE);
        }
        if (!hasCandidate(placement, panels, region, owner)) {
            return Result.error("插件 " + owner + " 在 " + nameOf(region) + " 区域没有面板\n"
                    + renderList(placement, panels)
                    + renderShortcuts(shortcutCandidates));
        }
        placement.assign(owner, region);
        return Result.ok(nameOf(region) + " 区域现在显示：" + owner);
    }

    /**
     * 判断某插件在指定区域是否有候选面板。
     *
     * @param placement 落位状态
     * @param panels    面板候选
     * @param region    区域
     * @param owner     插件标识
     * @return 有候选返回 {@code true}
     */
    private static boolean hasCandidate(UiPlacement placement, List<OwnedPanel> panels,
                                        UiRegion region, String owner) {
        List<OwnedPanel> list = placement.candidates(panels).get(region);
        if (list == null) {
            return false;
        }
        for (OwnedPanel panel : list) {
            if (panel.getOwner().equals(owner)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 渲染贡献清单。
     * <p>
     * 清单要回答三个问题：<b>哪些区域有东西</b>、<b>现在是哪个插件在用</b>、<b>还有没有别的候选</b>。
     * 最后一个尤其重要——用户看不到「被挤下去的面板」，不告诉他就会以为插件没生效。
     * <p>
     * 它是 {@code /ui list} 的全部内容，也是两级选择页的 {@code output} 兜底：选择页把每一条
     * 压成一行说明（见 {@link #briefOf}），而区域总览与快捷键诊断只有这里给得出来。
     *
     * @param placement 落位状态
     * @param panels    面板候选，可为 {@code null}
     * @return 清单文本，保证非 {@code null}
     */
    static String renderList(UiPlacement placement, List<OwnedPanel> panels) {
        Map<UiRegion, List<OwnedPanel>> candidates = placement.candidates(panels);
        Map<UiRegion, OwnedPanel> selected = placement.selected(panels);
        StringBuilder sb = new StringBuilder("插件界面区域：\n");
        for (UiRegion region : REGIONS.values()) {
            sb.append("  ").append(pad(nameOf(region))).append(describe(region, placement, candidates, selected))
                    .append('\n');
        }
        sb.append('\n').append(USAGE);
        if (panels == null || panels.isEmpty()) {
            sb.append("\n（当前没有任何插件贡献界面内容）");
        }
        return sb.toString();
    }

    /**
     * 渲染没生效的插件键位说明。
     * <p>
     * <b>为什么它归 {@code /ui} 而不是 {@code /help}</b>：{@code /help} 是「有什么可以用」，
     * 而这里回答的是「我绑的键为什么没反应」——那是个诊断问题，与面板被挤下去同一类。
     * <p>
     * 生效中的键位不在这里列（它们本来就会在按键时回显、也在 {@code /help} 里那条命令上），
     * 只列没生效的：一份全量表会让人以为「列出来的都能用」。
     *
     * @param candidates 说明行，可为 {@code null}
     * @return 文本片段；没有候选时为空串
     */
    private static String renderShortcuts(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n未生效的插件快捷键：");
        for (String candidate : candidates) {
            sb.append("\n  ").append(candidate);
        }
        return sb.toString();
    }

    /**
     * 描述一个区域的当前状态。
     *
     * @param region     区域
     * @param placement  落位状态
     * @param candidates 区域候选
     * @param selected   区域当前显示者
     * @return 描述文本
     */
    private static String describe(UiRegion region, UiPlacement placement,
                                   Map<UiRegion, List<OwnedPanel>> candidates,
                                   Map<UiRegion, OwnedPanel> selected) {
        List<OwnedPanel> list = candidates.get(region);
        if (list == null || list.isEmpty()) {
            return "[无贡献]";
        }
        if (region == UiRegion.STATUS) {
            return placement.isHidden(region)
                    ? "[关闭] 内核与插件的状态栏片段已隐藏"
                    : "[显示] " + list.size() + " 个插件贡献状态栏片段";
        }
        String state = placement.isHidden(region) ? "[关闭] " : "[显示] ";
        StringBuilder sb = new StringBuilder(state);
        OwnedPanel current = selected.get(region);
        if (current == null) {
            // 被关闭时 selected 里没有它，回落到候选首位，好让用户知道重新打开会看到谁
            current = list.get(0);
        }
        sb.append(current.getOwner());
        String title = titleOf(current);
        if (!title.isEmpty()) {
            sb.append(" \u00b7 ").append(title);
        }
        if (list.size() > 1) {
            sb.append("（另有 ").append(list.size() - 1).append(" 个候选：").append(otherOwners(list, current))
                    .append("，用 ").append(PREFIX).append(' ').append(nameOf(region)).append(' ')
                    .append(CYCLE).append(" 轮换）");
        }
        return sb.toString();
    }

    /**
     * 取面板标题，没有就返回空串。
     * <p>
     * 标题归插件给、可为空白，而清单与选择页都要在它缺失时不留一个悬空的间隔符，
     * 因此把「空白即没有」这一个判断收在这里。
     *
     * @param panel 面板
     * @return 标题文本，保证非 {@code null}
     */
    private static String titleOf(OwnedPanel panel) {
        String title = panel.getContribution().getTitle();
        return title == null || title.trim().isEmpty() ? "" : title.trim();
    }

    /**
     * 生成选择页上的一行简短说明。
     * <p>
     * <b>与 {@link #describe} 的分工</b>：那份是清单文本（可以长、可以带操作建议），
     * 这份要挤进选择页的一行说明列——那里还要跟标签分列，窄终端下只剩十几列。
     * 因此这里压到最短：状态 + 当前显示者 + 另有几个候选。
     *
     * @param region     区域
     * @param placement  落位状态
     * @param candidates 区域候选
     * @param selected   区域当前显示者
     * @return 说明文本，保证非 {@code null}
     */
    private static String briefOf(UiRegion region, UiPlacement placement,
                                  Map<UiRegion, List<OwnedPanel>> candidates,
                                  Map<UiRegion, OwnedPanel> selected) {
        if (region == UiRegion.STATUS) {
            // 状态栏是拼接型、没有「一块区域放一个」的语义，因此它不进 candidates（那是面板的分组），
            // 描述也就不能去数 list——片段有几条是另一条收集路径的事
            return placement.isHidden(region) ? "[关闭] 状态栏片段已隐藏" : "拼接型：所有片段共存";
        }
        List<OwnedPanel> list = candidates.get(region);
        if (list == null || list.isEmpty()) {
            return "无贡献";
        }
        String prefix = placement.isHidden(region) ? "[关闭] " : "[显示] ";
        OwnedPanel current = selected.get(region);
        if (current == null) {
            // 被关闭时 selected 里没有它，回落到候选首位，好让用户知道重新打开会看到谁
            current = list.get(0);
        }
        String extra = list.size() > 1 ? "（另有 " + (list.size() - 1) + " 个）" : "";
        return prefix + current.getOwner() + extra;
    }

    /**
     * 列出除当前显示者之外的候选。
     * <p>
     * 必须把名字列出来（而不是只给个数）：用户看不到被挤下去的面板，
     * 只告诉他一共有几个，他依旧无法用 {@code /ui <region> <pluginId>} 指定自己想要的。
     *
     * @param list    该区域的全部候选
     * @param current 当前显示者
     * @return 逗号分隔的 owner 列表，保证非 {@code null}
     */
    private static String otherOwners(List<OwnedPanel> list, OwnedPanel current) {
        StringBuilder sb = new StringBuilder();
        for (OwnedPanel panel : list) {
            if (panel.getOwner().equals(current.getOwner())) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(panel.getOwner());
        }
        return sb.toString();
    }

    /**
     * 把输入切成参数（去掉命令本身）。
     *
     * @param input 命令原文，可为 {@code null}
     * @return 参数列表，保证非 {@code null}
     */
    private static List<String> argsOf(String input) {
        if (input == null) {
            return new ArrayList<String>();
        }
        String[] parts = input.trim().split("\\s+");
        return parts.length <= 1 ? new ArrayList<String>() : new ArrayList<String>(Arrays.asList(parts).subList(1, parts.length));
    }

    /**
     * 取区域的命令行名字。
     *
     * @param region 区域
     * @return 名字，保证非 {@code null}
     */
    private static String nameOf(UiRegion region) {
        for (Map.Entry<String, UiRegion> entry : REGIONS.entrySet()) {
            if (entry.getValue() == region) {
                return entry.getKey();
            }
        }
        return region.name().toLowerCase();
    }

    /**
     * 左侧补空格，让清单的各列对齐。
     *
     * @param text 文本
     * @return 定宽文本
     */
    private static String pad(String text) {
        StringBuilder sb = new StringBuilder(text);
        while (sb.length() < 8) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * 构造区域名表。
     *
     * @return 有序映射（面板区域在前，状态栏殿后）
     */
    private static Map<String, UiRegion> regions() {
        Map<String, UiRegion> map = new LinkedHashMap<String, UiRegion>();
        map.put("dock", UiRegion.DOCK);
        map.put("top", UiRegion.TOP);
        map.put("left", UiRegion.LEFT);
        map.put("right", UiRegion.RIGHT);
        map.put("status", UiRegion.STATUS);
        return map;
    }

    /**
     * {@code /ui} 的执行结果：一段要贴到屏幕上的文本，外加它是否算失败；带候选时则要求外壳
     * 打开选择页而不是贴文本。
     * <p>
     * 不抛异常的原因：用户敲错区域名是最常见的输入错误，用异常表达会把它和「命令实现坏了」混在一起。
     *
     * @author zcd
     */
    static final class Result {

        /** 是否算失败（决定外壳提示用错误色还是常规色）。 */
        private final boolean error;

        /** 结果文本。 */
        private final String text;

        /** 候选；非空时外壳打开选择页，不再贴 {@link #text}。 */
        private final List<CommandChoice> choices;

        /**
         * 构造结果。
         *
         * @param error   是否算失败
         * @param text    结果文本
         * @param choices 候选，可为 {@code null}
         */
        private Result(boolean error, String text, List<CommandChoice> choices) {
            this.error = error;
            this.text = text;
            this.choices = choices == null
                    ? Collections.<CommandChoice>emptyList()
                    : Collections.unmodifiableList(new ArrayList<CommandChoice>(choices));
        }

        /**
         * 构造成功结果。
         *
         * @param text 结果文本
         * @return 结果
         */
        static Result ok(String text) {
            return new Result(false, text, null);
        }

        /**
         * 构造失败结果。
         *
         * @param text 结果文本
         * @return 结果
         */
        static Result error(String text) {
            return new Result(true, text, null);
        }

        /**
         * 构造一个「要求用户从候选里挑一个」的结果。
         * <p>
         * 文本仍然带上：候选为空时外壳不会打开选择页，那时候这段文本是用户唯一能看到的东西
         * （清单里「当前没有任何插件贡献」这类信息就靠它传出去）。
         *
         * @param text    结果文本（候选打不开时的兜底）
         * @param choices 候选
         * @return 结果
         */
        static Result choices(String text, List<CommandChoice> choices) {
            return new Result(false, text, choices);
        }

        /**
         * 判断是否算失败。
         *
         * @return 失败返回 {@code true}
         */
        boolean isError() {
            return error;
        }

        /**
         * 获取结果文本。
         *
         * @return 结果文本，保证非 {@code null}
         */
        String getText() {
            return text;
        }

        /**
         * 判断是否要求打开选择页。
         *
         * @return 有候选返回 {@code true}
         */
        boolean hasChoices() {
            return !choices.isEmpty();
        }

        /**
         * 获取候选。
         *
         * @return 候选列表，保证非 {@code null}、不可变
         */
        List<CommandChoice> getChoices() {
            return choices;
        }
    }
}
