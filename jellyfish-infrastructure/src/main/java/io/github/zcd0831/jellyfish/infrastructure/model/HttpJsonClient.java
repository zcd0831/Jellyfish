package io.github.zcd0831.jellyfish.infrastructure.model;

import io.github.zcd0831.jellyfish.application.shared.CancellationToken;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import okio.BufferedSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OkHttp 承载的 HTTP。规则出处：09-infrastructure §9.11、08-application §8.9.4.1。
 * <p><strong>请求句柄按调用持有</strong>：`Thread.interrupt` 对同步阻塞读无效，
 * 唯一能打断它的是 `Call.cancel()`；所以每个调用各自拿自己的 Call，
 * 不设“当前活跃请求”这种共享槽位（父会话与并行子代理共用一个实例）。
 */
public final class HttpJsonClient implements ChatTransport {

    static final int CONNECT_TIMEOUT_MS = 10_000;
    static final int READ_TIMEOUT_MS = 120_000;
    static final int STREAM_IDLE_TIMEOUT_MS = 60_000;

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;

    public HttpJsonClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    public Response post(String url, Map<String, String> headers, String body, CancellationToken token) {
        Call call = client.newCall(build(url, headers, body));
        Runnable unsubscribe = token.onCancel(cancelOf(call));
        try (okhttp3.Response response = call.execute()) {
            ResponseBody responseBody = response.body();
            String text = responseBody == null ? "" : responseBody.string();
            return new Response(response.code(), text, response.header("Retry-After"));
        } catch (IOException e) {
            if (token.isCancelled()) {
                throw new java.util.concurrent.CancellationException("在途请求已被终止：" + url);
            }
            throw new UncheckedIOException("模型端点不可达：" + url, e);
        } finally {
            unsubscribe.run();
        }
    }

    @Override
    public void postStreaming(String url, Map<String, String> headers, String body, CancellationToken token,
                              LineSink sink) {
        // 流式把读超时当"空闲超时"：每收到一行都会重置，长回答不会被总时长误杀
        OkHttpClient streaming = client.newBuilder()
                .readTimeout(STREAM_IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
        Call call = streaming.newCall(build(url, headers, body));
        Runnable unsubscribe = token.onCancel(cancelOf(call));
        try (okhttp3.Response response = call.execute()) {
            if (response.code() >= 400) {
                ResponseBody errorBody = response.body();
                throw new HttpStatusException(response.code(),
                        errorBody == null ? "" : errorBody.string(), response.header("Retry-After"));
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                return;
            }
            BufferedSource source = responseBody.source();
            String line;
            while ((line = source.readUtf8Line()) != null) {
                if (token.isCancelled()) {
                    throw new java.util.concurrent.CancellationException("在途流式读取已被终止：" + url);
                }
                if (line.startsWith("data:")) {
                    String data = line.substring("data:".length()).trim();
                    if (!sink.onData(data)) {
                        return;
                    }
                }
            }
        } catch (IOException e) {
            if (token.isCancelled()) {
                throw new java.util.concurrent.CancellationException("在途流式读取已被终止：" + url);
            }
            throw new UncheckedIOException("流式读取中断：" + url, e);
        } finally {
            unsubscribe.run();
        }
    }

    private static Runnable cancelOf(final Call call) {
        return new Runnable() {
            @Override
            public void run() {
                call.cancel();
            }
        };
    }

    private static Request build(String url, Map<String, String> headers, String body) {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body, JSON));
        for (Map.Entry<String, String> header : headers.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        return builder.build();
    }
}
