package zcd.jellyfish.server;

import zcd.jellyfish.api.ask.AskAnswer;
import zcd.jellyfish.infra.ask.AskChannel;
import zcd.jellyfish.server.dto.AskDto;
import zcd.jellyfish.server.http.ApiException;
import zcd.jellyfish.server.http.Responses;

import java.util.Optional;

/**
 * 提问通道与 HTTP 之间的桥：把 {@link AskChannel} 按会话的头槽位语义翻译成接口语义。
 * <p>
 * <b>为什么需要这一层</b>：三条路径（SSE 内嵌、{@code GET /asks}、{@code POST /asks/{id}}）
 * 读写的都是同一个事实，散在三处必然出现「一处按 id 比对、一处不比对」这类不一致。
 * 这里把「取本会话的头槽位」「跨会话最早一条」「按 id 作答」收成几个方法，三处调用点共用。
 * 形态与 {@link ApprovalBridge} 完全对称——两者的差别只在载荷类型与裁决语义。
 * <p>
 * <b>它是只读的一侧</b>：把提问摆到人面前的队列语义全在 {@code AskChannel} 里，
 * 这里只做投影与错误映射，不自己维护任何提问状态。
 * <p>
 * 无状态（只持有通道），可安全跨线程调用。
 *
 * @author zcd
 */
public final class AskBridge {

    /** 内核提问通道。 */
    private final AskChannel asks;

    /**
     * 构造桥。
     *
     * @param asks 内核提问通道，不可为 {@code null}
     */
    public AskBridge(AskChannel asks) {
        this.asks = asks;
    }

    /**
     * 挂上答复者：此后的提问会真的等待作答。
     * <p>
     * 与 {@code TuiRunMode} 完全对称：不挂时提问一律「无法送达用户」，模型据此自行判断。
     */
    public void attach() {
        asks.attach();
    }

    /**
     * 摘下答复者并收敛未决提问（服务停止时调用）。
     */
    public void detach() {
        asks.detach();
    }

    /**
     * 取当前头槽位待答提问（跨会话最早的那一条）。
     * <p>
     * 只服务「不知道自己是哪个会话」的晚到客户端（{@code GET /asks}）；
     * SSE 流应当用 {@link #headFor(String)} 取本会话的那一条。
     *
     * @return 待答提问；没有时为空
     */
    public Optional<AskDto> head() {
        return asks.pending().map(AskDto::of);
    }

    /**
     * 取属于指定会话的头槽位待答提问。
     * <p>
     * 只有属于本会话的头槽位才该发给本会话的 SSE 流。
     *
     * @param sessionId 会话标识
     * @return 待答提问；该会话没有待答提问时为空
     */
    public Optional<AskDto> headFor(String sessionId) {
        return asks.pending(sessionId).map(AskDto::of);
    }

    /**
     * 作答一条待答提问。
     *
     * @param requestId 请求标识
     * @param answer    答复，不可为 {@code null}
     * @throws ApiException 请求标识不是任何会话的头槽位（或已被作答 / 已超时）时抛出 404
     */
    public void resolve(String requestId, AskAnswer answer) {
        if (!asks.resolve(requestId, answer)) {
            throw new ApiException(Responses.NOT_FOUND, "ASK_NOT_FOUND",
                    "没有这条待答提问（可能已被作答或已超时）：" + requestId);
        }
    }

    /**
     * 收掉一条仍然待答的提问：供流断开时把卡在提问等待的 react 线程放行。
     * <p>
     * <b>用的是「取消」而不是「不可用」</b>：客户端断开意味着这个人已经不在这儿了，
     * 但服务本身仍然有答复者（只是没人连着了），因此终态要说「这次没问到」，
     * 而不是说「这里根本问不了人」——后者会把一个临时情况说成一台能力缺失。
     * <p>
     * 不做「找不到就报错」：断开路径上连客户端都没了，报错也无处可去；而这条提问可能刚好
     * 被别人答完，那正是最正常的情形。
     *
     * @param requestId 请求标识，可为 {@code null}
     */
    public void cancelIfPending(String requestId) {
        if (requestId == null) {
            return;
        }
        asks.resolve(requestId, AskAnswer.cancelled("提问的客户端已断开连接"));
    }
}
