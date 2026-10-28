package io.github.zcd0831.jellyfish.infrastructure.tool;

import io.github.zcd0831.jellyfish.application.action.port.ExternalToolPort;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * REST 工具的出站调用：把参数原样 POST 到 `source.endpointOrIdentifier`，超时用工具自带的。
 * 规则出处：05-action §5.8.1 规则 4（超时由工具自带）、09-infrastructure §9.4。
 */
public final class HttpToolAdapter implements ExternalToolPort {

    @Override
    public String invoke(ToolDefinition tool, Map<String, Object> args) {
        String endpoint = tool.source().getEndpointOrIdentifier();
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(tool.timeoutMillis() > 0 ? tool.timeoutMillis() : 10_000);
            connection.setReadTimeout(tool.timeoutMillis() > 0 ? tool.timeoutMillis() : 30_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(toJson(args).getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = readAll(stream);
            return status >= 200 && status < 300 ? body : "HTTP " + status + ": " + body;
        } catch (IOException e) {
            throw new UncheckedIOException("外部工具调用失败：" + endpoint, e);
        }
    }

    private static String toJson(Map<String, Object> args) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('"').append(escape(entry.getKey())).append("\":");
            Object value = entry.getValue();
            if (value == null) {
                out.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                out.append(value);
            } else if (value instanceof Map || value instanceof java.util.List) {
                out.append('"').append(escape(String.valueOf(value))).append('"');
            } else {
                out.append('"').append(escape(String.valueOf(value))).append('"');
            }
        }
        return out.append('}').toString();
    }

    private static String escape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
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
