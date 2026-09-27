package zcd.jellyfish.server.dto;

import zcd.jellyfish.api.extension.CommandChoice;

/**
 * 命令候选值：{@code GET /commands/{name}/options} 的返回元素。
 * <p>
 * 对应 api 侧 {@link CommandChoice}：{@code value} 是「选中后要追加为参数的东西」，
 * {@code label} 是展示名，{@code description} 是补充说明，{@code selected} 是当前会话下的选中态。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ChoiceDto {

    /** 候选值。 */
    private final String value;

    /** 展示名。 */
    private final String label;

    /** 补充说明，可为 {@code null}。 */
    private final String description;

    /** 是否当前选中。 */
    private final boolean selected;

    /**
     * 构造候选。
     *
     * @param value       候选值
     * @param label       展示名
     * @param description 补充说明，可为 {@code null}
     * @param selected    是否选中
     */
    public ChoiceDto(String value, String label, String description, boolean selected) {
        this.value = value;
        this.label = label;
        this.description = description;
        this.selected = selected;
    }

    /**
     * 把 api 候选投影成 DTO。
     *
     * @param choice api 候选，不可为 {@code null}
     * @return 候选 DTO
     */
    public static ChoiceDto of(CommandChoice choice) {
        return new ChoiceDto(choice.getValue(), choice.getLabel(), choice.getDescription(), choice.isCurrent());
    }

    /**
     * 获取候选值。
     *
     * @return 候选值
     */
    public String getValue() {
        return value;
    }

    /**
     * 获取展示名。
     *
     * @return 展示名
     */
    public String getLabel() {
        return label;
    }

    /**
     * 获取补充说明。
     *
     * @return 说明，可能为 {@code null}
     */
    public String getDescription() {
        return description;
    }

    /**
     * 判断是否当前选中。
     *
     * @return 选中返回 {@code true}
     */
    public boolean isSelected() {
        return selected;
    }
}
