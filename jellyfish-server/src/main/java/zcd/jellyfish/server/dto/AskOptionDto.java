package zcd.jellyfish.server.dto;

import zcd.jellyfish.api.ask.AskOption;

/**
 * 候选项：{@code ask_required} 事件载荷里的一项。
 * <p>
 * 投影一次的理由与 {@link ApprovalDto} 相同：HTTP 合同不跟内核类型走。
 * {@code optionId} 是作答时回填的东西，前端必须原样带回来——{@code label} 只用于显示，
 * 拿它当答案会让答案随界面文案变化而变化。
 * <p>
 * 客户端若要支持「用户自己填」，只须自行追加一项候选并在作答时改用请求体的 {@code text} 字段；
 * 「其它」那一项<b>不由服务端下发</b>——它是界面各自的表现，内核不替外壳决定要不要提供。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class AskOptionDto {

    /** 选项标识。 */
    private final String optionId;

    /** 给用户看的选项文案。 */
    private final String label;

    /** 补充说明，可为 {@code null}。 */
    private final String description;

    /**
     * 构造候选项。
     *
     * @param optionId    选项标识，可为 {@code null}
     * @param label       选项文案，可为 {@code null}
     * @param description 补充说明，可为 {@code null}
     */
    public AskOptionDto(String optionId, String label, String description) {
        this.optionId = optionId;
        this.label = label;
        this.description = description;
    }

    /**
     * 把内核候选项投影成 DTO。
     *
     * @param option 内核候选项，不可为 {@code null}
     * @return 候选项 DTO
     */
    public static AskOptionDto of(AskOption option) {
        return new AskOptionDto(option.getOptionId(), option.getLabel(), option.getDescription());
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
     * 获取选项文案。
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
}
