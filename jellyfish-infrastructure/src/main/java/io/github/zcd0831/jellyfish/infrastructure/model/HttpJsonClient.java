package io.github.zcd0831.jellyfish.infrastructure.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * JDK 自带 HTTP，不引第三方客户端（D16-5）；超时按 D10.1。
 * 流式用读超时表达"空闲超时"：每次读都重新计时，长回答不会被总时长误杀。
 */
final class HttpJsonClient implements ChatTransport {

    static final int CONNECT_TIMEOUT_MS = 10_000;
    static final int READ_TIMEOUT_MS = 120_000;
    static final int STREAM_IDLE_TIMEOUT_MS = 60_000;

    @Override
    public Response post(String url, Map<String, String> headers, String body) {
        HttpURLConnection connection = open(url, headers, body, READ_TIMEOUT_MS);
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(status, readAll(stream), connection.getHeaderField("Retry-After"));
        } catch (IOException e) {
            throw new UncheckedIOException("模型端点不可达：" + url, e);
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public void postStreaming(String url, Map<String, String> headers, String body, LineSink sink) {
        HttpURLConnection connection = open(url, headers, body, STREAM_IDLE_TIMEOUT_MS);
        try {
            if (connection.getResponseCode() >= 400) {
                throw new IllegalStateException("流式请求失败：HTTP " + connection.getResponseCode()
                        + " " + readAll(connection.getErrorStream()));
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
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
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url, Map<String, String> headers, String body, int readTimeout) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(readTimeout);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            return connection;
        } catch (IOException e) {
            throw new UncheckedIOException("建立连接失败：" + url, e);
        }
    }

    private static String readAll(InputStream stream) {
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream) {
            byte[] buffer = new byte[8192];
            StringBuilder out = new StringBuilder();
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
            return out.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
