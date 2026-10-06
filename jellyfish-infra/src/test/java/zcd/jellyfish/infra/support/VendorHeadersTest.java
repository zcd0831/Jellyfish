package zcd.jellyfish.infra.support;

import okhttp3.Request;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VendorHeaders} 的单元测试：保留头、头名清洗、覆盖语义与只读副本。
 *
 * @author zcd
 */
class VendorHeadersTest {

    @Test
    void sanitize_should_return_empty_map_when_raw_is_null() {
        // When / Then
        assertTrue(VendorHeaders.sanitize(null, "provider[openai]").isEmpty());
    }

    @Test
    void sanitize_should_drop_reserved_headers_case_insensitively() {
        // Given：HTTP 头名大小写不敏感，Content-Type 与 content-type 是同一个头
        Map<String, String> raw = mapOf("Content-Type", "text/plain", "content-type", "text/plain",
                "Accept", "application/json", "Host", "evil.example.com", "Content-Length", "1",
                "Accept-Encoding", "gzip", "Range", "bytes=0-10",
                "x-tenant", "t-1");

        // When
        Map<String, String> result = VendorHeaders.sanitize(raw, "provider[openai]");

        // Then：协议头与「由传输层为自己机制计算」的头全部丢弃，自定义头保留。
        // Accept-Encoding / Range 尤其隐蔽：用户自带会让传输层不再透明解压，响应体以压缩字节进解析
        assertEquals(Collections.singletonMap("x-tenant", "t-1"), result);
    }

    @Test
    void sanitize_should_drop_header_name_with_illegal_chars() {
        // Given：头名必须是 HTTP token（含中文、空格都不合法）
        Map<String, String> raw = mapOf("x 租户", "t-1", "x-tenant id", "t-1", "x-tenant", "t-1");

        // When
        Map<String, String> result = VendorHeaders.sanitize(raw, "provider[openai]");

        // Then：非法的在解析期就丢掉，而不是等到发请求时由 HTTP 客户端抛异常
        assertEquals(Collections.singletonMap("x-tenant", "t-1"), result);
    }

    @Test
    void sanitize_should_drop_header_value_with_illegal_chars() {
        // Given：头值只允许可见 ASCII 与制表符；换行与非 ASCII 都非法
        Map<String, String> raw = mapOf("x-tenant", "团队 A", "x-multiline", "a\nb", "x-ok", "t-1");

        // When
        Map<String, String> result = VendorHeaders.sanitize(raw, "provider[openai]");

        // Then：非法值在配置期丢弃——否则异常文本会把值原文（可能就是密钥）带进日志
        assertEquals(Collections.singletonMap("x-ok", "t-1"), result);
    }

    @Test
    void sanitize_should_trim_header_name_and_keep_value_verbatim() {
        // Given：头值里可能有合法的首尾空格，不能替用户去掉
        Map<String, String> raw = mapOf("  x-tenant  ", " spaced value ");

        // When
        Map<String, String> result = VendorHeaders.sanitize(raw, "provider[openai]");

        // Then
        assertEquals(" spaced value ", result.get("x-tenant"));
    }

    @Test
    void sanitize_should_drop_blank_name_and_null_value() {
        // Given
        Map<String, String> raw = new LinkedHashMap<String, String>();
        raw.put("   ", "v");
        raw.put("x-tenant", null);

        // When
        Map<String, String> result = VendorHeaders.sanitize(raw, "provider[openai]");

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void sanitize_should_return_unmodifiable_map() {
        // When
        Map<String, String> result = VendorHeaders.sanitize(mapOf("x-tenant", "t-1"), "provider[openai]");

        // Then
        assertThrows(UnsupportedOperationException.class, () -> result.put("x-other", "v"));
    }

    @Test
    void applyTo_should_override_kernel_header_with_user_value() {
        // Given：内核已写好鉴权头
        Request.Builder builder = new Request.Builder().url("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer kernel");

        // When：用户自定义了同名头
        VendorHeaders.applyTo(builder, mapOf("Authorization", "Bearer user", "x-tenant", "t-1"));

        // Then：同名头以用户为准（替换而不是追加成多值），其余头一起加上
        Request request = builder.build();
        assertEquals("Bearer user", request.header("Authorization"));
        assertEquals(1, request.headers("Authorization").size());
        assertEquals("t-1", request.header("x-tenant"));
    }

    @Test
    void applyTo_should_ignore_null_builder_and_empty_headers() {
        // Given
        Request.Builder builder = new Request.Builder().url("https://api.openai.com/v1/chat/completions");

        // When / Then：空头部不改变请求
        VendorHeaders.applyTo(builder, null);
        VendorHeaders.applyTo(builder, Collections.<String, String>emptyMap());
        VendorHeaders.applyTo(null, mapOf("x-tenant", "t-1"));
        Request request = builder.build();
        assertNull(request.header("x-tenant"));
    }

    /**
     * 构造一个有序映射。
     *
     * @param keyValues 交替出现的键与值
     * @return 映射
     */
    private static Map<String, String> mapOf(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
