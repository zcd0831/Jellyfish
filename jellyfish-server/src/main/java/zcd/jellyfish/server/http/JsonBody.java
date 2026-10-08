package zcd.jellyfish.server.http;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * 请求体读取：把字节流按上限读出来并反序列化成 DTO。
 * <p>
 * <b>为什么必须有上限</b>：{@code Content-Length} 可以缺省（分块传输），因此「先信任头再读」不够；
 * 真正的护栏是「读到超过上限就停」。没有它，一个不设限的 {@code POST} 就能把进程的内存吃光。
 * <p>
 * <b>为什么空体返回 {@code null} 而不是报错</b>：有些端点的请求体是可选的
 * （例如 {@code POST /sessions} 允许完全不带 body，表示全取默认），把「没有体」与「体非法」
 * 分开表达，处理器才写得清楚。
 *
 * @author zcd
 */
public final class JsonBody {

    /** 单次读取的缓冲区大小。 */
    private static final int BUFFER_SIZE = 8192;

    /** 我们接受的媒体类型。 */
    private static final String JSON_MEDIA_TYPE = "application/json";

    /** 结构化 JSON 的媒体类型后缀（{@code application/vnd.api+json} 这类）。 */
    private static final String JSON_SUFFIX = "+json";

    /**
     * 工具类，禁止实例化。
     */
    private JsonBody() {
    }

    /**
     * 读取并反序列化请求体。
     *
     * @param exchange HTTP 交换对象，不可为 {@code null}
     * @param type     目标 DTO 类型，不可为 {@code null}
     * @param maxBytes 请求体字节上限
     * @param <T>      目标类型
     * @return 反序列化结果；请求体为空时返回 {@code null}
     * @throws ApiException 体过大（413）、媒体类型不是 JSON（415）、读取失败或反序列化失败（400）时抛出
     */
    public static <T> T read(HttpServerExchange exchange, Class<T> type, int maxBytes) {
        requireJsonContentType(exchange);
        long declared = exchange.getRequestContentLength();
        if (declared > maxBytes) {
            throw new ApiException(Responses.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                    "请求体过大：" + declared + " 字节，上限 " + maxBytes + " 字节");
        }
        byte[] body = readBytes(exchange, maxBytes);
        if (body.length == 0) {
            return null;
        }
        try {
            return ObjectMapperWrapper.readValue(body, type);
        } catch (JellyfishException e) {
            throw new ApiException(Responses.BAD_REQUEST, Responses.CODE_BAD_REQUEST,
                    "请求体不是合法 JSON 或字段类型不符", e);
        }
    }

    /**
     * 校验请求体的媒体类型是 JSON。
     * <p>
     * <b>为什么声明了就要求是 JSON</b>：浏览器能跨站发出的「简单请求」只有三种内容类型
     * （{@code text/plain}、{@code application/x-www-form-urlencoded}、{@code multipart/form-data}），
     * 它们<b>不触发预检</b>——也就是说，一个恶意页面可以让你的浏览器带着本服务的 cookie / 内网可达性去
     * POST 一个「看起来是 JSON」的体，副作用照常发生（只是它读不到响应）。而不声明 JSON 的调用方
     * 本来就该被拒：那种请求既不像浏览器表单、也不像 SDK，把它的体当成 JSON 解析只会得到难懂的 400。
     * <p>
     * <b>没带这个头就放行</b>：不猜。判断依据只能是「调用方声明了什么」，凭空拒绝会把
     * 只用 curl {@code -d} 之类的正常用法一起挡掉（它们会声明 {@code application/x-www-form-urlencoded}，
     * 那正好落进上面那类，被拒是预期的——加一个 {@code -H 'Content-Type: application/json'} 即可）。
     *
     * @param exchange HTTP 交换对象
     * @throws ApiException 声明了非 JSON 的媒体类型时抛出 415
     */
    private static void requireJsonContentType(HttpServerExchange exchange) {
        String declared = exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE);
        if (declared == null || declared.trim().isEmpty()) {
            return;
        }
        String mediaType = declared.trim();
        int semicolon = mediaType.indexOf(';');
        if (semicolon >= 0) {
            // 参数（charset 等）与判定无关，去掉再比
            mediaType = mediaType.substring(0, semicolon).trim();
        }
        mediaType = mediaType.toLowerCase(Locale.ROOT);
        if (JSON_MEDIA_TYPE.equals(mediaType) || mediaType.endsWith(JSON_SUFFIX)) {
            return;
        }
        throw new ApiException(Responses.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "请求体必须是 JSON：期望 Content-Type: application/json，收到 " + mediaType);
    }

    /**
     * 按上限读取请求体字节。
     *
     * @param exchange HTTP 交换对象
     * @param maxBytes 上限
     * @return 请求体字节，保证非 {@code null}
     * @throws ApiException 超过上限（413）或读取失败（400）时抛出
     */
    private static byte[] readBytes(HttpServerExchange exchange, int maxBytes) {
        InputStream in = exchange.getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[BUFFER_SIZE];
        int total = 0;
        try {
            int read;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new ApiException(Responses.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                            "请求体超过上限 " + maxBytes + " 字节");
                }
                buffer.write(chunk, 0, read);
            }
        } catch (IOException e) {
            // 客户端中途断开属于请求侧问题，不是服务端故障
            throw new ApiException(Responses.BAD_REQUEST, Responses.CODE_BAD_REQUEST, "读取请求体失败", e);
        }
        return buffer.toByteArray();
    }
}
