package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 命令候选查询结果：命令在「无参数」时可供用户挑选的取值清单。
 * <p>
 * <b>为什么不复用 {@link CommandResult}</b>：候选查询是<b>只读</b>的——问「这条命令有哪些取值」，
 * 而不是「执行这条命令」。用执行结果承载它，会把「查询」与「执行」混成一条语义，
 * 也让「选中即弹选择页」这条路径看起来像发生过副作用。因此给它一个独立结果类型。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class CommandOptions {

    /** 空候选：命令没有可选值。 */
    private static final CommandOptions EMPTY = new CommandOptions(null);

    /** 候选清单，保证非 {@code null}。 */
    private final List<CommandChoice> choices;

    /**
     * 构造候选查询结果。
     *
     * @param choices 候选清单，可为 {@code null} 或空
     */
    private CommandOptions(List<CommandChoice> choices) {
        this.choices = copyChoices(choices);
    }

    /**
     * 构造带候选的结果。
     *
     * @param choices 候选清单，可为 {@code null} 或空（等价 {@link #empty()}）
     * @return 候选查询结果
     */
    public static CommandOptions of(List<CommandChoice> choices) {
        return choices == null || choices.isEmpty() ? EMPTY : new CommandOptions(choices);
    }

    /**
     * 获取空候选结果。
     *
     * @return 没有任何可选值的结果
     */
    public static CommandOptions empty() {
        return EMPTY;
    }

    /**
     * 获取候选清单。
     *
     * @return 不可变候选清单，保证非 {@code null}
     */
    public List<CommandChoice> getChoices() {
        return choices;
    }

    /**
     * 判断是否没有候选。
     *
     * @return 没有候选返回 {@code true}
     */
    public boolean isEmpty() {
        return choices.isEmpty();
    }

    @Override
    public String toString() {
        return "CommandOptions{choices=" + choices.size() + '}';
    }

    /**
     * 复制候选清单并拒绝 {@code null} 元素。
     *
     * @param choices 原始候选清单，可为 {@code null}
     * @return 不可变副本，保证非 {@code null}
     */
    private static List<CommandChoice> copyChoices(List<CommandChoice> choices) {
        if (choices == null || choices.isEmpty()) {
            return Collections.emptyList();
        }
        List<CommandChoice> copy = new ArrayList<CommandChoice>(choices.size());
        for (CommandChoice choice : choices) {
            if (choice == null) {
                throw new JellyfishException("command choice must not be null");
            }
            copy.add(choice);
        }
        return Collections.unmodifiableList(copy);
    }
}
