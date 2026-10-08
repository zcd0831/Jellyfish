package zcd.jellyfish.infra.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.config.Provider;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * LlmClient 实现内部的公共工具方法。
 *
 * @author zcd
 */
public final class LlmClients {

    /** 空闲连接数上限。 */
    private static final int MAX_IDLE_CONNECTIONS = 10;

    /** 空闲连接保活时间（分钟）。 */
    private static final long KEEP_ALIVE_MINUTES = 5L;

    /** 建立连接的超时（秒）。 */
    private static final long CONNECT_TIMEOUT_SECONDS = 30L;

    /** 写出请求的超时（秒）。 */
    private static final long WRITE_TIMEOUT_SECONDS = 60L;

    /** 读响应的超时（秒）。 */
    private static final long READ_TIMEOUT_SECONDS = 120L;

    private LlmClients() {
    }

    /**
     * 构造全局共享的 HTTP 客户端：所有 LLM 客户端共用同一个连接池。
     * <p>
     * <b>刻意关掉重定向</b>：密钥在这套请求里走的是<b>自定义头</b>（{@code x-api-key}、
     * {@code x-goog-api-key}），而 OkHttp 在跨主机重定向时只剥 {@code Authorization}——
     * 一个上游 {@code 302} 就足以把这些头送到任意主机上去。上游端点本来也不该重定向，
     * 因此这里选择「不跟」而不是「跟着但校验目标」：少一条能出错的路径。
     * 真被拦下来时错误信息里会带上 {@code Location}（见 {@code AbstractHttpLlmClient}），
     * 用户据此把 {@code baseUrl} 直接配成最终地址即可。
     * <p>
     * {@code followSslRedirects} 与 {@code followRedirects} 都要关：前者管协议间跳转
     * （{@code http→https}），只关后者仍会跟。
     *
     * @return HTTP 客户端，保证非 {@code null}
     */
    public static OkHttpClient newHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectionPool(new ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
                .build();
    }

    public static String requireApiKey(Provider provider) {
        String apiKey = provider.getApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new JellyfishException("apiKey is required for provider: " + provider.getName());
        }
        return apiKey.trim();
    }

    public static String resolveBaseUrl(Provider provider, String defaultBaseUrl) {
        String baseUrl = provider.getBaseUrl();
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            baseUrl = defaultBaseUrl;
        }
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new JellyfishException("baseUrl is required for provider: " + provider.getName());
        }
        return stripTrailingSlash(baseUrl.trim());
    }

    public static String stripTrailingSlash(String url) {
        String result = url;
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /**
     * 在 baseUrl 后拼接版本段和路径：
     * <pre>
     *   https://api.openai.com            + v1 + /chat/completions -&gt; https://api.openai.com/v1/chat/completions
     *   https://api.openai.com/v1         + v1 + /chat/completions -&gt; https://api.openai.com/v1/chat/completions
     * </pre>
     * 即 baseUrl 已经包含目标版本段时不再重复拼接。
     */
    public static String appendVersion(String baseUrl, String version, String path) {
        String base = stripTrailingSlash(baseUrl);
        String suffix = "/" + version;
        if (base.length() >= suffix.length()
                && base.regionMatches(true, base.length() - suffix.length(), suffix, 0, suffix.length())) {
            return base + path;
        }
        return base + suffix + path;
    }

    public static String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        return value.asText();
    }

    public static String firstNonBlank(String first, String second) {
        return isNotBlank(first) ? first : second;
    }

    public static boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public static String nullableString(CharSequence value) {
        if (value == null || value.length() == 0) {
            return null;
        }
        return value.toString();
    }

    public static String toJson(Object value) {
        return ObjectMapperWrapper.writeValueAsString(value);
    }

    /**
     * 将工具调用参数（JSON 字符串）解析为对象，用于回传给厂商。
     */
    public static Map<String, Object> parseArguments(String arguments) {
        if (!isNotBlank(arguments)) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> parsed =
                    ObjectMapperWrapper.readValue(arguments, new TypeReference<Map<String, Object>>() {
                    });
            return parsed == null ? Collections.<String, Object>emptyMap() : parsed;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    public static String normalizeToolChoice(String toolChoice) {
        return toolChoice == null ? "" : toolChoice.trim().toLowerCase(Locale.ROOT);
    }
}
