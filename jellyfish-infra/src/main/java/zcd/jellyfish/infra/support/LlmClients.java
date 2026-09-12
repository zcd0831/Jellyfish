package zcd.jellyfish.infra.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.config.Provider;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/**
 * LlmClient 实现内部的公共工具方法。
 *
 * @author zcd
 */
public final class LlmClients {

    private LlmClients() {
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
     *   https://api.openai.com            + v1 + /chat/completions -> https://api.openai.com/v1/chat/completions
     *   https://api.openai.com/v1         + v1 + /chat/completions -> https://api.openai.com/v1/chat/completions
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
