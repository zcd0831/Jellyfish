package io.github.zcd0831.jellyfish.infrastructure.model;

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
 * OkHttp 承载的 HTTP：超时按 D10.1，取消按 D05-4（`Call.cancel()` 让阻塞读立刻失败）。
 * 规则出处：技术决策 D16-5、D10.1、M14。
 */
public final class HttpJsonClient implements ChatTransport {

    static final int CONNECT_TIMEOUT_MS = 10_000;
    static final int READ_TIMEOUT_MS = 120_000;
    static final int STREAM_IDLE_TIMEOUT_MS = 60_000;

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private volatile Call active;
    /** 只对当次调用有效：每次发请求开头清掉。 */
    private volatile boolean cancelled;

    public HttpJsonClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    public Response post(String url, Map<String, String> headers, String body) {
        cancelled = false;
        Request request = build(url, headers, body);
        Call call = client.newCall(request);
        active = call;
        try (okhttp3.Response response = call.execute()) {
            ResponseBody responseBody = response.body();
            String text = responseBody == null ? "" : responseBody.string();
            return new Response(response.code(), text, response.header("Retry-After"));
        } catch (IOException e) {
            throw new UncheckedIOException("模型端点不可达：" + url, e);
        } finally {
            active = null;
        }
    }

    @Override
    public void postStreaming(String url, Map<String, String> headers, String body, LineSink sink) {
        cancelled = false;
        // 流式把读超时当"空闲超时"：每收到一行都会重置，长回答不会被总时长误杀（D10.1）
        OkHttpClient streaming = client.newBuilder()
                .readTimeout(STREAM_IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
        Call call = streaming.newCall(build(url, headers, body));
        active = call;
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
                if (line.startsWith("data:")) {
                    String data = line.substring("data:".length()).trim();
                    if (!sink.onData(data)) {
                        return;
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("流式读取中断：" + url, e);
        } finally {
            active = null;
        }
    }

    @Override
    public void cancelActive() {
        cancelled = true;
        Call call = active;
        if (call != null) {
            call.cancel();
        }
    }

    @Override
    public boolean cancelledByUser() {
        return cancelled;
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
