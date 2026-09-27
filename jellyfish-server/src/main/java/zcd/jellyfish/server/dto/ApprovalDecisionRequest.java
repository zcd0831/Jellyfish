package zcd.jellyfish.server.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code POST /approvals/{requestId}} 的请求体。
 * <p>
 * <b>为什么 {@code approved} 用包装类型 {@link Boolean}</b>：缺字段与显式 {@code false} 必须区分开——
 * 前者是「请求写错了」（400），后者是「用户点了拒绝」（正常的裁决）。用基本类型会让缺字段静默变成拒绝。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ApprovalDecisionRequest {

    /** 是否批准；{@code null} 表示未提供。 */
    private final Boolean approved;

    /**
     * 构造请求。
     *
     * @param approved 是否批准，可为 {@code null}
     */
    @JsonCreator
    public ApprovalDecisionRequest(@JsonProperty("approved") Boolean approved) {
        this.approved = approved;
    }

    /**
     * 获取裁决结果。
     *
     * @return 是否批准，可能为 {@code null}
     */
    public Boolean getApproved() {
        return approved;
    }
}
