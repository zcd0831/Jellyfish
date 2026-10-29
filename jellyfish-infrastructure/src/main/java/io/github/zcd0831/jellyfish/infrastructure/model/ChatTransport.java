package io.github.zcd0831.jellyfish.infrastructure.model;

import io.github.zcd0831.jellyfish.application.shared.CancellationToken;

import java.util.Map;

/**
 * 只做 HTTP：状态码 + 响应体 / SSE 数据行。协议映射是 {@link ChatCompletionsProtocol} 的事，
 * 这样测试可以不碰网络。规则出处：09-infrastructure §9.11。
 * <p>终止信号<strong>随调用下行</strong>，不是共享槽位：父会话与并行子代理共用一个实现，
 * 槽位一旦共享，一个会话的终止就会错打到另一个会话的在途请求上（08-application §8.11）。
 */
public interface ChatTransport {

    Response post(String url, Map<String, String> headers, String body, CancellationToken token);

    void postStreaming(String url, Map<String, String> headers, String body, CancellationToken token, LineSink sink);

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
