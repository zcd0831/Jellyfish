package zcd.jellyfish.tui;

import zcd.jellyfish.infra.command.CommandInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 命令补全：把「输入框里正在打的命令词」变成一份候选清单与一个选中项。
 * <p>
 * <b>纯逻辑、无渲染、无终端</b>：它只回答「现在要不要弹补全、弹哪几条、选中第几条、接受后文本变成什么」。
 * 渲染交给 {@link CommandCompletionView}，按键交给 {@link InputKeyMapper} 与 {@link TuiApp}。
 * 这样补全的判定规则（最容易出错、也最需要覆盖边界的那部分）可以脱离终端单测。
 * <p>
 * <b>激活条件刻意很窄</b>：输入只有一行、以 {@code /} 开头、且 {@code /} 之后到行尾都没有空白。
 * 也就是说「正在打命令名」才弹；一旦敲了空格（开始给参数）就收起。理由有两条：
 * <ol>
 *     <li>参数补全需要每个命令自己的补全器，命令域没有这个信息，假装能补只会给出错误候选；</li>
 *     <li>{@code /mode} 这类命令的参数是自由文本，用户敲空格往往就是不想看列表了。</li>
 * </ol>
 * <p>
 * <b>为什么需要「已关闭」状态</b>：{@link #refresh} 每帧都由输入文本现算，若无记忆，
 * 用户按 {@code Esc} 收起后下一帧又会弹出来——那看起来像按键失灵。因此记住「被关掉的那个命令词」，
 * 只有当输入词再次变化时才允许重开。
 * <p>
 * <b>线程契约</b>：与 {@link ChatState} 相同，只由渲染线程读写。
 *
 * @author zcd
 */
public final class CommandCompletion {

    /** 面板最多显示的行数：再多就会把消息区挤没，而补全只是临时浮层。 */
    static final int MAX_VISIBLE = 8;

    /** 命令前缀：与 {@code CommandManager.COMMAND_PREFIX} 同源，补全只做文本拼接，故就近声明。 */
    static final String PREFIX = "/";

    /** 是否处于补全态。 */
    private boolean active;

    /** 当前候选，保证非 {@code null}。 */
    private List<CommandInfo> candidates = Collections.emptyList();

    /** 当前选中项下标。 */
    private int selected;

    /** 上一次计算时输入框里的命令词（不含前缀），未处于补全上下文时为 {@code null}。 */
    private String token;

    /** 被 {@link #dismiss()} 关掉的命令词：输入词与它相同则不再重开。 */
    private String dismissedToken;

    /**
     * 按当前输入重算补全状态。
     * <p>
     * 每帧调用；「命令词没变」时只做一次前缀过滤，成本 O(命令数)，可以忽略。
     *
     * @param inputText 输入框原文，可为 {@code null}
     * @param available 全部可用命令，可为 {@code null}（当作没有命令）
     */
    public void refresh(String inputText, List<CommandInfo> available) {
        String currentToken = commandToken(inputText);
        if (currentToken == null) {
            // 离开补全上下文（换行 / 空格 / 不是以 / 开头）：清空一切，包括「已关闭」记忆，
            // 否则下次输入同一个命令词时会莫名其妙不再弹出
            reset();
            dismissedToken = null;
            return;
        }
        if (currentToken.equals(dismissedToken)) {
            active = false;
            candidates = Collections.emptyList();
            selected = 0;
            token = currentToken;
            return;
        }
        dismissedToken = null;
        if (!currentToken.equals(token)) {
            // 命令词变了，旧的选中位置不再有意义
            selected = 0;
        }
        token = currentToken;
        active = true;
        candidates = filter(available, currentToken);
        if (selected >= candidates.size()) {
            selected = candidates.isEmpty() ? 0 : candidates.size() - 1;
        }
    }

    /**
     * 判断当前是否应显示补全面板。
     *
     * @return 显示返回 {@code true}
     */
    public boolean isActive() {
        return active;
    }

    /**
     * 获取当前候选清单。
     *
     * @return 不可变候选列表，保证非 {@code null}
     */
    public List<CommandInfo> getCandidates() {
        return candidates;
    }

    /**
     * 获取选中项下标。
     *
     * @return 下标；无候选时为 0
     */
    public int getSelectedIndex() {
        return selected;
    }

    /**
     * 获取选中项。
     *
     * @return 选中项；无候选时为 {@code null}
     */
    public CommandInfo selected() {
        return selected < candidates.size() ? candidates.get(selected) : null;
    }

    /**
     * 选中项上移一项，到头后回到末尾。
     */
    public void moveUp() {
        if (candidates.isEmpty()) {
            return;
        }
        selected = (selected - 1 + candidates.size()) % candidates.size();
    }

    /**
     * 选中项下移一项，到尾后回到开头。
     */
    public void moveDown() {
        if (candidates.isEmpty()) {
            return;
        }
        selected = (selected + 1) % candidates.size();
    }

    /**
     * 接受当前选中项，给出替换后的输入文本。
     * <p>
     * 命令带用法片段（如 {@code /agent <agentId>}）时补一个空格，让用户直接接着打参数；
     * 不带用法的命令只留命令名，避免发送时多一个尾随空格。
     *
     * @return 替换后的输入文本；无选中项时返回 {@code null}
     */
    public String accept() {
        CommandInfo info = selected();
        if (info == null) {
            return null;
        }
        String usage = info.getUsage();
        return PREFIX + info.getName() + (usage == null || usage.isEmpty() ? "" : " ");
    }

    /**
     * 收起面板，并在当前命令词上留下「已关闭」记忆。
     * <p>
     * 用户按 {@code Esc} 或接受候选后调用；只要输入词不再变化，{@link #refresh} 就不会重新弹开。
     */
    public void dismiss() {
        if (active) {
            dismissedToken = token;
        }
        active = false;
        candidates = Collections.emptyList();
        selected = 0;
    }

    /**
     * 清空补全状态。
     * <p>
     * 刻意不动 {@link #dismissedToken}：它的生命周期是「一个命令词」，由 {@link #refresh} 统一管理。
     */
    private void reset() {
        active = false;
        candidates = Collections.emptyList();
        selected = 0;
        token = null;
    }

    /**
     * 从输入原文提取正在输入的命令词（不含前缀）。
     * <p>
     * 返回 {@code null} 表示当前不处于「打命令名」的上下文：不是以 {@code /} 开头、含有换行，
     * 或命令词之后已经出现空白（开始给参数了）。
     *
     * @param inputText 输入原文，可为 {@code null}
     * @return 命令词（可能是空串，表示只打了一个 {@code /}）；不处于补全上下文时返回 {@code null}
     */
    static String commandToken(String inputText) {
        if (inputText == null || inputText.isEmpty() || inputText.charAt(0) != '/') {
            return null;
        }
        String body = inputText.substring(1);
        for (int i = 0; i < body.length(); i++) {
            if (Character.isWhitespace(body.charAt(i))) {
                return null;
            }
        }
        return body;
    }

    /**
     * 按前缀过滤命令：命令名或任一别名以命令词开头即命中。
     *
     * @param available 全部命令，可为 {@code null}
     * @param token     命令词（不含前缀），不可为 {@code null}
     * @return 不可变候选列表，保持传入顺序（命令域已按命令名升序），保证非 {@code null}
     */
    private static List<CommandInfo> filter(List<CommandInfo> available, String token) {
        if (available == null || available.isEmpty()) {
            return Collections.emptyList();
        }
        String lower = token.toLowerCase(Locale.ROOT);
        List<CommandInfo> matched = new ArrayList<CommandInfo>();
        for (CommandInfo info : available) {
            if (matches(info, lower)) {
                matched.add(info);
            }
        }
        return Collections.unmodifiableList(matched);
    }

    /**
     * 判断一条命令是否前缀命中。
     *
     * @param info  命令清单项
     * @param lower 已转小写的命令词
     * @return 命中返回 {@code true}
     */
    private static boolean matches(CommandInfo info, String lower) {
        if (info.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
            return true;
        }
        for (String alias : info.getAliases()) {
            if (alias.toLowerCase(Locale.ROOT).startsWith(lower)) {
                return true;
            }
        }
        return false;
    }
}
