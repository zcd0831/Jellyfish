package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.ui.UiEmphasis;
import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.api.ui.UiRegion;
import zcd.jellyfish.api.ui.UiSegment;
import zcd.jellyfish.script.ScriptJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面板贡献编解码：{@code PanelContributionRequest} ↔ {@code PanelContribution}。
 * <p>
 * 协议形状：
 * <pre>
 *   request : {"sessionId":"s-1"}
 *   result  : {"title":"Jira","region":"DOCK",
 *              "lines":[{"segments":[{"text":"PROJ-1 ","emphasis":"NORMAL"},
 *                                    {"text":"●","emphasis":"ACCENT"}]}]}
 * </pre>
 * <b>形态是「独占型」</b>：一块区域同时只显示一个面板，多个插件抢同一区域时由用户用 {@code /ui} 切换，
 * 因此 {@code region} 只是<b>软建议</b>——外壳可以忽略它，插件不得假设自己一定显示、也不得假设显示在
 * 自己想的位置。
 * <p>
 * <b>插件无权控制尺寸</b>：{@code lines} 只是「我要显示这些行」，行数上限、宽度折行、超长截断全由外壳决定。
 * <p>
 * <b>两处刻意的宽容</b>（都遵循 api 里对应类型的既有约定）：
 * <ul>
 *     <li>无法识别的 {@code region} → 按「未指定」处理（旧外壳遇到新枚举值同理，见 {@code UiRegion} 注释）；</li>
 *     <li>无法识别的 {@code emphasis} → 退回 {@code NORMAL}，宁可样式不对也不要整块面板消失。</li>
 * </ul>
 *
 * @author zcd
 */
public final class PanelCodec implements ExtensionCodec<PanelContributionRequest, PanelContribution> {

    /** 协议类型名，同时是清单 {@code contributions} 的取值。 */
    public static final String TYPE_NAME = "panel";

    /** 结果载荷里的标题字段名。 */
    private static final String FIELD_TITLE = "title";

    /** 结果载荷里的落位字段名。 */
    private static final String FIELD_REGION = "region";

    /** 结果载荷里的内容行字段名。 */
    private static final String FIELD_LINES = "lines";

    /** 内容行的文本段字段名。 */
    private static final String FIELD_SEGMENTS = "segments";

    /** 文本段的文本字段名。 */
    private static final String FIELD_TEXT = "text";

    /** 文本段的强调字段名。 */
    private static final String FIELD_EMPHASIS = "emphasis";

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public Class<PanelContributionRequest> requestType() {
        return PanelContributionRequest.class;
    }

    @Override
    public boolean isTypeLevel() {
        // 多个插件各给一块：内核用 bindings(PanelContributionRequest.class, null) 收集
        return true;
    }

    @Override
    public JsonNode encodeRequest(PanelContributionRequest request) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("sessionId", request.getSessionId());
        return ScriptJson.treeOf(payload);
    }

    @Override
    public PanelContribution decodeResult(JsonNode result, String routeKey) {
        if (result == null || !result.isObject()) {
            return PanelContribution.empty();
        }
        return PanelContribution.of(Payloads.text(result, FIELD_TITLE), lines(result.get(FIELD_LINES)),
                region(Payloads.text(result, FIELD_REGION)));
    }

    /**
     * 解析内容行。
     *
     * @param node 行数组节点，可为 {@code null}
     * @return 内容行列表，保证非 {@code null}；无内容时为空列表（即空面板）
     */
    private static List<UiLine> lines(JsonNode node) {
        List<UiLine> lines = new ArrayList<UiLine>();
        if (node == null || !node.isArray()) {
            return lines;
        }
        for (JsonNode element : node) {
            lines.add(line(element));
        }
        return lines;
    }

    /**
     * 解析单行：一个元素是文本段数组，允许直接用字符串简写整行。
     * <p>
     * 支持字符串简写是刻意的：绝大多数面板行是单段纯文本，逼脚本写成
     * {@code {"segments":[{"text":"…"}]}} 只会让示例代码变长而没有任何表达力收益。
     *
     * @param node 行节点，可为 {@code null}
     * @return 逻辑行，保证非 {@code null}
     */
    private static UiLine line(JsonNode node) {
        if (node == null || node.isNull()) {
            return UiLine.EMPTY;
        }
        if (node.isTextual()) {
            return UiLine.of(node.asText());
        }
        return new UiLine(segments(node.get(FIELD_SEGMENTS)));
    }

    /**
     * 解析文本段数组。
     *
     * @param node 文本段数组节点，可为 {@code null}
     * @return 文本段列表，保证非 {@code null}
     */
    private static List<UiSegment> segments(JsonNode node) {
        List<UiSegment> segments = new ArrayList<UiSegment>();
        if (node == null || !node.isArray()) {
            return segments;
        }
        for (JsonNode element : node) {
            if (element == null || !element.isObject()) {
                continue;
            }
            String text = Payloads.text(element, FIELD_TEXT);
            if (text == null) {
                continue;
            }
            segments.add(UiSegment.of(text, emphasis(Payloads.text(element, FIELD_EMPHASIS))));
        }
        return segments;
    }

    /**
     * 解析落位建议，无法识别时返回 {@code null}。
     *
     * @param name 落位名，可为 {@code null}
     * @return 落位区域或 {@code null}
     */
    private static UiRegion region(String name) {
        if (name == null) {
            return null;
        }
        for (UiRegion candidate : UiRegion.values()) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 解析强调级别，无法识别时退回 {@code NORMAL}。
     *
     * @param name 强调名，可为 {@code null}
     * @return 强调级别，保证非 {@code null}
     */
    private static UiEmphasis emphasis(String name) {
        if (name != null) {
            for (UiEmphasis candidate : UiEmphasis.values()) {
                if (candidate.name().equals(name)) {
                    return candidate;
                }
            }
        }
        return UiEmphasis.NORMAL;
    }
}
