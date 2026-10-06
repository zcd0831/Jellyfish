package zcd.jellyfish.server.dto;

import zcd.jellyfish.api.ask.AskOption;
import zcd.jellyfish.infra.ask.AskChannel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 待答提问：SSE 的 {@code ask_required} 事件与 {@code GET /asks} 的载荷。
 * <p>
 * 字段与 {@link AskChannel.Pending} 一一对应，投影一次的理由与 {@link ApprovalDto} 相同：
 * HTTP 合同不跟内核类型走。与审批载荷的唯一结构差异是 {@code question} 与 {@code options}——
 * 审批给的是「工具名 + 参数 + 理由」，提问给的是「问题 + 候选项」。
 * <p>
 * <b>关于「同一时刻只有一条」</b>：与审批同口径，头槽位是<b>每会话一个</b>，会话之间互不排队；
 * 同一会话内仍是「一个头槽位 + FIFO 队列」，排队中的提问对外不可见。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class AskDto {

    /** 请求标识。 */
    private final String requestId;

    /** 会话标识，可为 {@code null}。 */
    private final String sessionId;

    /** 发起提问的来源（工具名等），可为 {@code null}。 */
    private final String source;

    /** 问题原文。 */
    private final String question;

    /** 候选项，保证非 {@code null}。 */
    private final List<AskOptionDto> options;

    /** 提问发生时刻（epoch millis）。 */
    private final long timestamp;

    /**
     * 构造待答提问。
     *
     * @param requestId 请求标识
     * @param sessionId 会话标识，可为 {@code null}
     * @param source    发起提问的来源，可为 {@code null}
     * @param question  问题原文，可为 {@code null}
     * @param options   候选项，可为 {@code null}
     * @param timestamp 提问发生时刻
     */
    public AskDto(String requestId, String sessionId, String source, String question,
                  List<AskOptionDto> options, long timestamp) {
        this.requestId = requestId;
        this.sessionId = sessionId;
        this.source = source;
        this.question = question;
        this.options = options == null
                ? Collections.<AskOptionDto>emptyList()
                : Collections.unmodifiableList(new ArrayList<AskOptionDto>(options));
        this.timestamp = timestamp;
    }

    /**
     * 把内核待答提问投影成 DTO。
     *
     * @param pending 内核待答提问，不可为 {@code null}
     * @return 待答提问 DTO
     */
    public static AskDto of(AskChannel.Pending pending) {
        List<AskOptionDto> options = new ArrayList<AskOptionDto>(pending.getRequest().getOptions().size());
        for (AskOption option : pending.getRequest().getOptions()) {
            options.add(AskOptionDto.of(option));
        }
        return new AskDto(pending.getId(), pending.getSessionId(), pending.getRequest().getSource(),
                pending.getRequest().getQuestion(), options, pending.getTimestamp());
    }

    /**
     * 获取请求标识。
     *
     * @return 请求标识
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * 获取会话标识。
     *
     * @return 会话标识，可能为 {@code null}
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取发起提问的来源。
     *
     * @return 来源，可能为 {@code null}
     */
    public String getSource() {
        return source;
    }

    /**
     * 获取问题原文。
     *
     * @return 问题原文，可能为 {@code null}
     */
    public String getQuestion() {
        return question;
    }

    /**
     * 获取候选项。
     *
     * @return 只读候选列表，保证非 {@code null}
     */
    public List<AskOptionDto> getOptions() {
        return options;
    }

    /**
     * 获取提问发生时刻。
     *
     * @return 时刻（epoch millis）
     */
    public long getTimestamp() {
        return timestamp;
    }
}
