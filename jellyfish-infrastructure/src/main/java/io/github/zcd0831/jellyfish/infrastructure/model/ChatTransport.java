package io.github.zcd0831.jellyfish.infrastructure.model;

import java.util.Map;

/**
 * 只做 HTTP：状态码 + 响应体 / SSE 数据行。协议映射是 {@link ChatCompletionsProtocol} 的事，
 * 这样测试可以不碰网络。规则出处：09-infrastructure §9.11。
 */
public interface ChatTransport {

    Response post(String url, Map<String, String> headers, String body);

    void postStreaming(String url, Map<String, String> headers, String body, LineSink sink);

    /** 取消在途请求：让阻塞读立刻失败。 */
    void cancelActive();

    /**
     * 本次调用是否被用户取消：取消是终态，重试循环必须据此直接退出。
     * 放在传输层，是因为"是否被打断"只有发请求的一方知道，不必让令牌穿过每个调用点。
     */
    boolean cancelledByUser();

    interface LineSink {
        /** 收到一条 SSE `data:` 负载；返回 false 表示不再继续读。 */
        boolean onData(String data);
    }

    final class Response {

        private final int status;
        private final String body;
        private final String retryAfter;

        Response(int status, String body, String retryAfter) {
            this.status = status;
            this.body = body;
            this.retryAfter = retryAfter;
        }

        int status() {
            return status;
        }

        String body() {
            return body;
        }

        String retryAfter() {
            return retryAfter;
        }
    }
}
