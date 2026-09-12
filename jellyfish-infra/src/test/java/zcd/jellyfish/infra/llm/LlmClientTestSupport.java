package zcd.jellyfish.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import zcd.jellyfish.infra.config.Provider;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * LLM 客户端单元测试的公共支撑。
 * <p>
 * 所有 HTTP 交互都通过 OkHttp 拦截器在内存中返回固定响应，不打开任何真实连接；
 * 流式测试使用「调用线程直接执行」的线程池，保证回调在断言前按序完成。
 *
 * @author zcd
 */
final class LlmClientTestSupport {

    /** SSE 响应使用的媒体类型。 */
    static final String SSE_CONTENT_TYPE = "text/event-stream";

    /** JSON 响应使用的媒体类型。 */
    static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    private LlmClientTestSupport() {
    }

    /**
     * 构造测试用 provider。
     *
     * @param type    provider 类型
     * @param apiKey  apiKey
     * @param baseUrl baseUrl
     * @return provider 实例
     */
    static Provider provider(String type, String apiKey, String baseUrl) {
        return new Provider("test-" + type, type, apiKey, baseUrl, Collections.emptyList());
    }

    /**
     * 构造返回指定 JSON 响应体的拦截器桩。
     *
     * @param body 响应体
     * @return 拦截器桩
     */
    static StubInterceptor jsonStub(String body) {
        return new StubInterceptor(200, body, JSON_CONTENT_TYPE);
    }

    /**
     * 构造返回 SSE 流响应体的拦截器桩。
     *
     * @param body SSE 响应体
     * @return 拦截器桩
     */
    static StubInterceptor sseStub(String body) {
        return new StubInterceptor(200, body, SSE_CONTENT_TYPE);
    }

    /**
     * 构造返回指定状态码与 JSON 响应体的拦截器桩。
     *
     * @param code 状态码
     * @param body 响应体
     * @return 拦截器桩
     */
    static StubInterceptor errorStub(int code, String body) {
        return new StubInterceptor(code, body, JSON_CONTENT_TYPE);
    }

    /**
     * 构造在建立连接阶段直接抛 IO 异常的拦截器桩。
     *
     * @return 拦截器桩
     */
    static StubInterceptor failureStub() {
        return new StubInterceptor(0, null, null);
    }

    /**
     * 构造同步执行的线程池，保证流式回调在测试线程内完成。
     *
     * @return 直接执行的线程池
     */
    static ExecutorService directExecutor() {
        return new DirectExecutorService();
    }

    /**
     * 读取请求体文本。
     *
     * @param request HTTP 请求
     * @return 请求体文本
     * @throws IOException 读取失败时抛出
     */
    static String requestBody(Request request) throws IOException {
        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        return buffer.readUtf8();
    }

    /**
     * 解析 JSON 文本。
     *
     * @param text JSON 文本
     * @return JSON 树根节点
     */
    static JsonNode json(String text) {
        return ObjectMapperWrapper.readTree(text);
    }

    /**
     * 记录全部流式事件的监听器，便于对事件序列做断言。
     *
     * @author zcd
     */
    static final class RecordingListener implements LlmStreamListener {

        /** 收到的文本增量。 */
        final List<String> texts = new ArrayList<>();

        /** 收到的思考过程增量。 */
        final List<String> thinkings = new ArrayList<>();

        /** 收到的工具调用快照。 */
        final List<LlmToolCall> toolCalls = new ArrayList<>();

        /** onOpen 触发次数。 */
        int openCount;

        /** 正常结束时的聚合结果。 */
        LlmResponse completed;

        /** 是否被取消。 */
        boolean cancelled;

        /** 失败原因。 */
        Throwable error;

        @Override
        public void onOpen() {
            openCount++;
        }

        @Override
        public void onText(String delta) {
            texts.add(delta);
        }

        @Override
        public void onThinking(String delta) {
            thinkings.add(delta);
        }

        @Override
        public void onToolCall(LlmToolCall toolCall) {
            toolCalls.add(toolCall);
        }

        @Override
        public void onComplete(LlmResponse response) {
            completed = response;
        }

        @Override
        public void onCancelled() {
            cancelled = true;
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
        }
    }

    /**
     * 记录最后一次请求并返回固定响应的拦截器桩。
     * <p>
     * 状态码为 0 时表示在建立连接阶段抛出 {@link IOException}，用于模拟网络失败。
     *
     * @author zcd
     */
    static final class StubInterceptor implements Interceptor {

        /** 响应状态码，0 表示模拟网络异常。 */
        private final int code;

        /** 响应体。 */
        private final String body;

        /** 响应体媒体类型。 */
        private final MediaType mediaType;

        /** 最后一次被拦截的请求。 */
        private volatile Request lastRequest;

        /**
         * 构造拦截器桩。
         *
         * @param code        状态码，0 表示模拟网络异常
         * @param body        响应体
         * @param contentType 响应体媒体类型
         */
        private StubInterceptor(int code, String body, String contentType) {
            this.code = code;
            this.body = body;
            this.mediaType = contentType == null ? null : MediaType.get(contentType);
        }

        /**
         * 获取最后一次被拦截的请求。
         *
         * @return 最后一次请求，尚未发起时为 {@code null}
         */
        Request lastRequest() {
            return lastRequest;
        }

        /**
         * 构造使用本桩的离线 OkHttp 客户端。
         *
         * @return OkHttp 客户端
         */
        OkHttpClient client() {
            return new OkHttpClient.Builder().addInterceptor(this).build();
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            lastRequest = chain.request();
            if (mediaType == null) {
                throw new IOException("simulated network failure");
            }
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("test")
                    .body(ResponseBody.create(body, mediaType))
                    .build();
        }
    }

    /**
     * 在调用线程同步执行任务的线程池。
     *
     * @author zcd
     */
    private static final class DirectExecutorService extends AbstractExecutorService {

        /** 是否已关闭。 */
        private volatile boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }
}
