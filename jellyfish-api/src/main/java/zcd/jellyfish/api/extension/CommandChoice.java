package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

/**
 * 命令候选值：命令在「需要用户从若干取值里挑一个」时给出的一个选项。
 * <p>
 * <b>它回答的问题</b>：{@code /agent} 这类命令不带参数时，究竟有哪些 agent 可绑？
 * 命令域没有参数字典，外壳也无法替命令猜——只有命令自己知道候选集合，
 * 因此由处理器在 {@link CommandResult} 里一并给出，外壳据此渲染二级选择页。
 * <p>
 * <b>{@code value} 与 {@code label} 分开</b>：{@code value} 是选中后要追加到命令名之后的参数原文
 * （如 {@code coder}、{@code openai/gpt-4o}），{@code label} 是给人看的显示文本。
 * 两者通常相同，但允许不同（例如 label 带说明性前缀），因此不能合并成一个字段。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class CommandChoice {

    /** 选中后追加为命令参数的取值原文。 */
    private final String value;

    /** 显示文本。 */
    private final String label;

    /** 补充说明（如 agent 描述），未提供时为 {@code null}。 */
    private final String description;

    /** 是否为当前已选中的取值（外壳据此标记「当前」）。 */
    private final boolean current;

    /**
     * 构造命令候选值。
     *
     * @param value       取值原文，不可为空白
     * @param label       显示文本，可为 {@code null} 或空白（回退为 {@code value}）
     * @param description 补充说明，可为 {@code null}
     * @param current     是否为当前取值
     * @throws JellyfishException {@code value} 为空白时抛出
     */
    public CommandChoice(String value, String label, String description, boolean current) {
        if (value == null || value.trim().isEmpty()) {
            throw new JellyfishException("command choice value must not be blank");
        }
        this.value = value;
        this.label = label == null || label.isEmpty() ? value : label;
        this.description = description;
        this.current = current;
    }

    /**
     * 构造一个「非当前、无说明」的候选值。
     *
     * @param value 取值原文，不可为空白
     * @param label 显示文本，可为 {@code null} 或空白（回退为 {@code value}）
     * @throws JellyfishException {@code value} 为空白时抛出
     */
    public CommandChoice(String value, String label) {
        this(value, label, null, false);
    }

    /**
     * 获取取值原文（追加到命令名之后的参数）。
     *
     * @return 取值原文，保证非 {@code null}
     */
    public String getValue() {
        return value;
    }

    /**
     * 获取显示文本。
     *
     * @return 显示文本，保证非 {@code null}
     */
    public String getLabel() {
        return label;
    }

    /**
     * 获取补充说明。
     *
     * @return 补充说明，未提供时为 {@code null}
     */
    public String getDescription() {
        return description;
    }

    /**
     * 判断是否为当前取值。
     *
     * @return 是当前取值返回 {@code true}
     */
    public boolean isCurrent() {
        return current;
    }

    @Override
    public String toString() {
        return "CommandChoice{value=" + value + ", current=" + current + '}';
    }
}
