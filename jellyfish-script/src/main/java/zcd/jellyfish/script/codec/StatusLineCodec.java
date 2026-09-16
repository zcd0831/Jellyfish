package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.extension.StatusLineContribution;
import zcd.jellyfish.api.extension.StatusLineContributionRequest;
import zcd.jellyfish.script.ScriptJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 状态栏贡献编解码：{@code StatusLineContributionRequest} ↔ {@code StatusLineContribution}。
 * <p>
 * 协议形状：
 * <pre>
 *   request : {"sessionId":"s-1"}
 *   result  : {"text":"待办 2/5"}          // null 或缺省 = 无片段
 * </pre>
 * <b>形态是「拼接型」</b>：状态栏是一行，多个插件的片段共存而不是互相抢占，因此这个扩展点没有落位概念。
 * 文本请自带归属（内核<b>不加</b> pluginId 前缀），因为状态栏通常只剩 30～60 列。
 * <p>
 * <b>处理器必须满足三条硬约束</b>：纯只读、不得发布 {@code UiInvalidatedEvent}、必须快——
 * 外壳在缓存失效时收集一次并<b>在渲染线程内联执行</b>。脚本插件在这里天然吃亏（一次进程往返
 * 就是毫秒级），因此本扩展点对脚本的实际价值有限，但开放它才能保证「与 Java 插件同权」这句话成立。
 * 外壳可能永远不问（{@code -cli} 单次模式没有界面）。
 *
 * @author zcd
 */
public final class StatusLineCodec
        implements ExtensionCodec<StatusLineContributionRequest, StatusLineContribution> {

    /** 协议类型名，同时是清单 {@code contributions} 的取值。 */
    public static final String TYPE_NAME = "status_line";

    /** 结果载荷里的文本字段名。 */
    private static final String FIELD_TEXT = "text";

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public Class<StatusLineContributionRequest> requestType() {
        return StatusLineContributionRequest.class;
    }

    @Override
    public boolean isTypeLevel() {
        // 多个插件各给一段：内核用 bindings(StatusLineContributionRequest.class, null) 收集
        return true;
    }

    @Override
    public JsonNode encodeRequest(StatusLineContributionRequest request) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("sessionId", request.getSessionId());
        return ScriptJson.treeOf(payload);
    }

    @Override
    public StatusLineContribution decodeResult(JsonNode result, String routeKey) {
        return StatusLineContribution.of(Payloads.text(result, FIELD_TEXT));
    }
}
