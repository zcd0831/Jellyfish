package zcd.jellyfish.infra.support;

import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code models.json} 里 {@code vendorHeaders} 的清洗与应用：把用户自定义的请求头加进出站请求。
 * <p>
 * <b>段名为什么以 {@code vendor} 开头</b>：同 {@link VendorBody}——它属于「内核不解释、原样发出」的那一类，
 * 与内核定义并校验的 {@code sampling} 段分属两条边界。它承载的通常是厂商或企业网关在文档之外要求的头部。
 * <p>
 * <b>为什么需要它</b>：企业网关、自建代理、区域端点常要求一个厂商文档之外的头部
 * （自定义鉴权、租户标识、路由标签），而内核不认识它们，也不该为此发version。
 * 与 {@code vendorBody} 同一立场：<b>只搬运、不解释</b>，用户比内核更清楚自己的端点认什么。
 * <p>
 * <b>它只挂在 provider 上</b>（模型级没有这个字段）：请求头是<b>端点</b>属性，同一个 provider 下的
 * 所有模型共享同一套地址、鉴权与网关规则；做成模型级只会多出「两个模型发不同头部」这种
 * 没人能验证、也没人需要的能力。
 * <p>
 * <b>用户头部覆盖内核同名头部，但有六个头必须挡住</b>（见 {@link #RESERVED_HEADERS}）：
 * 与 {@code vendorBody} 里挡结构性键同一个理由——允许覆盖 {@code Content-Type} / {@code Accept} 不是
 * 「可能坏事」而是「必然坏事」：前者让厂商拒收请求体，后者让流式响应按普通 JSON 解析。反过来，
 * <b>鉴权类头部是放行的</b>：网关要求的自定义鉴权头正是这条路的正经用途，而密钥本来就是用户自己的。
 * <p>
 * 头部名按 HTTP 语义<b>大小写不敏感</b>地判重与判保留（{@code Content-Type} 与 {@code content-type}
 * 是同一个头），因此这里统一转小写比较，<b>下发时保留用户写的原始形式</b>。
 * <p>
 * <b>头名与头值还按 HTTP 的字符集规则校验</b>（头名须为 token、头值只允许可见 ASCII 与制表符）：
 * 不校验的话，含中文或换行的配置能一路走到发请求那一刻，由 HTTP 客户端每次调用抛一个带<b>值原文</b>的
 * 异常——而那个值可能就是密钥。校验放在解析配置时，与其余配置问题同一口径：丢弃 + 告警，日志只留头名。
 * <p>
 * <b>头部值可能与密钥同级敏感</b>（自定义鉴权头就是密钥）：本类不把它写进任何日志，
 * 调用方也不得把带有用户头部的请求对象写进日志。
 *
 * @author zcd
 */
public final class VendorHeaders {

    /**
     * 保留头部：由内核按协议语义生成，用户覆盖它只会弄坏请求。
     * <p>
     * 放这六个的理由分两类：{@code Content-Type} 决定厂商怎么解析请求体、{@code Accept} 决定流式还是
     * 普通 JSON——写错就是请求被拒或流式响应按普通 JSON 解析；{@code Host}、{@code Content-Length}、
     * {@code Accept-Encoding} 与 {@code Range} 由 HTTP 客户端按实际连接、实体与自身机制计算
     * （{@code Accept-Encoding} 与 {@code Range} 尤其隐蔽：一旦由用户自带，传输层就不再走
     * <b>透明解压</b>那条路径，响应体将以压缩字节送进 JSON 解析，症状是「配了个看起来无害的头，
     * 所有请求开始报解析错误」）。
     */
    private static final Set<String> RESERVED_HEADERS = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList("content-type", "accept", "host", "content-length",
                    "accept-encoding", "range")));

    /** 配置期告警用；请求期不刷日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(VendorHeaders.class);

    /** 工具类，不实例化。 */
    private VendorHeaders() {
    }

    /**
     * 清洗一段 {@code vendorHeaders}：丢弃保留头、空白头名与空值，并把结果包装为只读映射。
     *
     * @param raw   原始配置值，可为 {@code null}
     * @param owner 归属描述（如 {@code provider[openai]}），仅用于告警文本
     * @return 只读副本；入参为空时返回空映射而非 {@code null}
     */
    public static Map<String, String> sanitize(Map<String, String> raw, String owner) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.trim().isEmpty()) {
                LOG.warn("vendorHeaders 的空白头名已丢弃: owner={}", owner);
                continue;
            }
            String trimmed = name.trim();
            if (RESERVED_HEADERS.contains(trimmed.toLowerCase(Locale.ROOT))) {
                LOG.warn("vendorHeaders 的保留头已丢弃（它由内核按协议生成）: owner={} header={}", owner, trimmed);
                continue;
            }
            String value = entry.getValue();
            if (value == null) {
                LOG.warn("vendorHeaders 的头值为空，已丢弃: owner={} header={}", owner, trimmed);
                continue;
            }
            if (!isValidName(trimmed)) {
                LOG.warn("vendorHeaders 的头名含非法字符，已丢弃: owner={} header={}", owner, trimmed);
                continue;
            }
            if (!isValidValue(value)) {
                // 不把值打进日志：错的可能正是密钥本身（自定义鉴权头），而头名足够定位
                LOG.warn("vendorHeaders 的头值含非法字符（换行、非 ASCII 等），已丢弃: owner={} header={}",
                        owner, trimmed);
                continue;
            }
            result.put(trimmed, value);
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 判断头名是否为合法的 HTTP token。
     * <p>
     * <b>为什么在这里校验而不是交给 HTTP 客户端</b>：非法的头名与头值要在<b>发请求时</b>才由
     * okhttp 抛出 {@code IllegalArgumentException}，而那是在热路径上、每次调用都抛，
     * 且异常文本会带上头值原文（可能把密钥带进日志与失败事件）。校验放在解析配置时，
     * 口径与其余配置问题一致：丢弃 + 告警，不阻断启动，日志里只出现头名。
     *
     * @param name 头名
     * @return 合法时返回 {@code true}
     */
    private static boolean isValidName(String name) {
        for (int i = 0; i < name.length(); i++) {
            if (!isTokenChar(name.charAt(i))) {
                return false;
            }
        }
        return !name.isEmpty();
    }

    /**
     * 判断头值是否只含 HTTP 允许的可见 ASCII 与制表符。
     *
     * @param value 头值
     * @return 合法时返回 {@code true}
     */
    private static boolean isValidValue(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\t' && (c < 0x20 || c > 0x7e)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断字符是否属于 HTTP token 允许的集合（RFC 7230 的 {@code tchar}）。
     *
     * @param c 待判断字符
     * @return 属于 token 字符时返回 {@code true}
     */
    private static boolean isTokenChar(char c) {
        if (c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
            return true;
        }
        return "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }

    /**
     * 把自定义头部加到请求构建器上。
     * <p>
     * 用 {@code header} 而不是 {@code addHeader}：同名头是<b>替换</b>语义。这既是「用户头部覆盖内核
     * 同名头部」这条约定的实现，也避免了同名头被追加成多值——{@code anthropic-version} 出现两次时
     * 端点行为各家不同，那不是一个能验证的结果。
     *
     * @param builder 请求构建器，不可为 {@code null}
     * @param headers 已清洗的头部映射，可为 {@code null}
     */
    public static void applyTo(Request.Builder builder, Map<String, String> headers) {
        if (builder == null || headers == null || headers.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
    }
}
