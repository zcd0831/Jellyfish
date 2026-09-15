package zcd.jellyfish.tui;

import zcd.jellyfish.api.extension.CommandChoice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 二级选择页状态：命令给出候选后，用户在候选里挑一个。
 * <p>
 * <b>它解决什么问题</b>：{@code /agent}、{@code /model}、{@code /mode}、{@code /resume} 这类命令
 * 不带参数时，需要用户从若干取值里挑一个。命令域只给出候选清单（{@link CommandChoice}），
 * 「怎么显示、光标停在哪、选中后拼什么命令」是外壳的事——本类就是后者的纯逻辑部分。
 * <p>
 * <b>与 {@link CommandCompletion} 的分工</b>：补全是「边打边过滤命令名」，本类是「命令已经发出、
 * 现在挑一个参数」。两者都占用输入框上方的浮层位置，但状态互斥：选择页激活时外壳不再渲染补全。
 * <p>
 * <b>线程契约</b>：与 {@link ChatState} 相同，只由渲染线程读写。
 *
 * @author zcd
 */
public final class CommandChoicePicker {

    /** 是否处于选择态。 */
    private boolean active;

    /** 触发选择页的命令原文（含前缀，如 {@code /agent}），用于拼出带参数的后续命令。 */
    private String baseCommand = "";

    /** 当前候选，保证非 {@code null}。 */
    private List<CommandChoice> choices = Collections.emptyList();

    /** 当前选中项下标。 */
    private int selected;

    /**
     * 打开选择页。
     * <p>
     * 候选为空时不打开：命令已经给了文本输出，外壳照旧打印它即可，弹一个空页面毫无意义。
     * 默认选中「当前取值」（若有），让用户打开页面就能看清自己在哪一档。
     *
     * @param baseCommand 触发选择页的命令原文（如 {@code /agent}），可为 {@code null}
     * @param choices     候选清单，可为 {@code null} 或空（不打开）
     */
    public void open(String baseCommand, List<CommandChoice> choices) {
        if (choices == null || choices.isEmpty()) {
            return;
        }
        this.baseCommand = baseCommand == null ? "" : baseCommand;
        this.choices = Collections.unmodifiableList(new ArrayList<CommandChoice>(choices));
        this.selected = currentIndex();
        this.active = true;
    }

    /**
     * 判断当前是否应显示选择页。
     *
     * @return 显示返回 {@code true}
     */
    public boolean isActive() {
        return active;
    }

    /**
     * 获取触发选择页的命令原文。
     *
     * @return 命令原文（含前缀），保证非 {@code null}
     */
    public String getBaseCommand() {
        return baseCommand;
    }

    /**
     * 获取当前候选清单。
     *
     * @return 不可变候选列表，保证非 {@code null}
     */
    public List<CommandChoice> getChoices() {
        return choices;
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
    public CommandChoice selected() {
        return selected < choices.size() ? choices.get(selected) : null;
    }

    /**
     * 选中项上移一项，到头后回到末尾。
     */
    public void moveUp() {
        if (choices.isEmpty()) {
            return;
        }
        selected = (selected - 1 + choices.size()) % choices.size();
    }

    /**
     * 选中项下移一项，到尾后回到开头。
     */
    public void moveDown() {
        if (choices.isEmpty()) {
            return;
        }
        selected = (selected + 1) % choices.size();
    }

    /**
     * 关闭选择页并清空状态。
     */
    public void dismiss() {
        active = false;
        baseCommand = "";
        choices = Collections.emptyList();
        selected = 0;
    }

    /**
     * 求「当前取值」的下标，没有则回退到 0。
     *
     * @return 下标
     */
    private int currentIndex() {
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).isCurrent()) {
                return i;
            }
        }
        return 0;
    }
}
