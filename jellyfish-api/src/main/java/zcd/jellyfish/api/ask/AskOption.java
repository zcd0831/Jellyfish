package zcd.jellyfish.api.ask;

/**
 * 一次提问里的一个候选项。
 * <p>
 * <b>为什么带 id 而不只有文案</b>：界面上显示的是给人读的 {@link #getLabel()}，而回传给模型的
 * 是稳定的 {@link #getOptionId()}——两者分开之后，文案可以随界面宽度折行、可以带说明，
 * 答案却仍是一个不会变形的短标识。省略 id 时内核按序号（{@code "1"}、{@code "2"}…）补齐，
 * 因此提问方不必为每个选项都编一个名字。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class AskOption {

    /** 选项标识，供回传与日志使用。 */
    private final String optionId;

    /** 给用户看的选项文案。 */
    private final String label;

    /** 选项的补充说明，可为 {@code null}。 */
    private final String description;

    /**
     * 构造候选项。
     * <p>
     * 跨边界值类型只有这一个可见构造器；日常构造请用静态工厂。
     *
     * @param optionId    选项标识，可为 {@code null}（由提问方按序号补齐）
     * @param label       给用户看的选项文案，不可为空白
     * @param description 补充说明，可为 {@code null}
     */
    public AskOption(String optionId, String label, String description) {
        this.optionId = optionId;
        this.label = label;
        this.description = description;
    }

    /**
     * 构造只有文案的候选项。
     *
     * @param label 给用户看的选项文案，不可为空白
     * @return 候选项，保证非 {@code null}
     */
    public static AskOption of(String label) {
        return new AskOption(null, label, null);
    }

    /**
     * 构造带标识与说明的候选项。
     *
     * @param optionId    选项标识，可为 {@code null}
     * @param label       给用户看的选项文案，不可为空白
     * @param description 补充说明，可为 {@code null}
     * @return 候选项，保证非 {@code null}
     */
    public static AskOption of(String optionId, String label, String description) {
        return new AskOption(optionId, label, description);
    }

    /**
     * 获取选项标识。
     *
     * @return 选项标识，可能为 {@code null}
     */
    public String getOptionId() {
        return optionId;
    }

    /**
     * 获取给用户看的选项文案。
     *
     * @return 选项文案，可能为 {@code null}
     */
    public String getLabel() {
        return label;
    }

    /**
     * 获取补充说明。
     *
     * @return 补充说明，可能为 {@code null}
     */
    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return "AskOption{optionId=" + optionId + ", label=" + label + '}';
    }
}
