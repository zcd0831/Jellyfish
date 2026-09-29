package zcd.jellyfish.plugin.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code tools/call} 结果的渲染：把协议里的 content 数组落成一段给模型看的文本。
 * <p>
 * <b>为什么内容块要分类型处理</b>：{@code text} 是正文，{@code image} / {@code audio} 是二进制
 * （base64 直接塞进上下文会撑爆窗口），{@code resource} 是一个引用（有内联正文就用，没有就给 URI）。
 * 一律当字符串拼，最小的那种失败是「模型拿到一整屏 base64」，而它既看不懂也删不掉。
 * <p>
 * <b>未知类型不丢</b>：协议会加新类型，而「不认识就跳过」会让模型看到一份看似正常、实则缺了内容的
 * 结果。这里退化成一段带类型的 JSON 摘要——它不好看，但它诚实地说明「这里有一块我没法渲染的东西」。
 * <p>
 * 不能实例化。
 *
 * @author zcd
 */
final class McpContent {

    /** 单个内容块 JSON 摘要的最大长度。 */
    private static final int MAX_RAW_CHARS = 2000;

    /**
     * 工具类，禁止实例化。
     */
    private McpContent() {
    }

    /**
     * 渲染一次工具调用的结果。
     *
     * @param result   协议返回的 result 节点，可为 {@code null}
     * @param spill    二进制落盘器，不可为 {@code null}
     * @param serverId 服务标识
     * @param toolName 工具名
     * @return 回灌文本，保证非 {@code null}
     */
    static String render(JsonNode result, McpMediaSpill spill, String serverId, String toolName) {
        if (result == null) {
            return "（工具返回了空结果）";
        }
        JsonNode content = McpJson.childArray(result, "content");
        if (content == null || content.size() == 0) {
            // 没有 content 时把整个 result 作为 JSON 摘要给出去：总比「空结果」多一条线索
            return summarize(result);
        }
        List<String> blocks = new ArrayList<String>();
        for (JsonNode item : content) {
            blocks.add(renderBlock(item, spill, serverId, toolName));
        }
        return join(blocks);
    }

    /**
     * 渲染单个内容块。
     *
     * @param item     内容块节点
     * @param spill    二进制落盘器
     * @param serverId 服务标识
     * @param toolName 工具名
     * @return 该块的文本
     */
    private static String renderBlock(JsonNode item, McpMediaSpill spill, String serverId,
                                      String toolName) {
        String type = McpJson.text(item, "type", "");
        if ("text".equals(type)) {
            return McpJson.text(item, "text", "");
        }
        if ("image".equals(type) || "audio".equals(type)) {
            String mimeType = McpJson.text(item, "mimeType", type + "/unknown");
            String data = McpJson.text(item, "data", "");
            int bytes = approximateBytes(data);
            String path = spill.spill(data, mimeType, serverId, toolName);
            if (path == null) {
                return "[" + type + " 内容：" + mimeType + "，约 " + bytes + " 字节，未落盘]";
            }
            return "[" + type + " 内容已保存：" + path + "（" + mimeType + "，约 " + bytes + " 字节）]";
        }
        if ("resource".equals(type)) {
            return renderResource(McpJson.childObject(item, "resource"));
        }
        return summarize(item);
    }

    /**
     * 渲染资源引用块。
     * <p>
     * 有内联正文（{@code text}）就直接给正文——那正是 server 想让我们看到的；
     * 只有 {@code blob} 或只有 URI 时给引用，并说明需要额外读取。
     *
     * @param resource 资源节点，可为 {@code null}
     * @return 该块的文本
     */
    private static String renderResource(JsonNode resource) {
        if (resource == null) {
            return "[无法解析的 resource 内容块]";
        }
        String uri = McpJson.text(resource, "uri", "");
        String inline = McpJson.text(resource, "text", null);
        if (inline != null) {
            return "[" + uri + "]\n" + inline;
        }
        return "[" + uri + " 的内容未内联，需要时另行读取]";
    }

    /**
     * 用 JSON 摘要兜底一个无法识别的内容块或结果。
     *
     * @param node 节点
     * @return 摘要文本
     */
    private static String summarize(JsonNode node) {
        String json = McpJson.write(node);
        return json.length() <= MAX_RAW_CHARS ? json : json.substring(0, MAX_RAW_CHARS) + "…";
    }

    /**
     * 拼接多个块。
     *
     * @param blocks 块文本列表
     * @return 拼接结果
     */
    private static String join(List<String> blocks) {
        StringBuilder text = new StringBuilder();
        for (String block : blocks) {
            if (block == null || block.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(block);
        }
        return text.length() == 0 ? "（工具返回了空内容）" : text.toString();
    }

    /**
     * 估算 base64 文本解码后的字节数。
     *
     * @param base64Data base64 文本，可为 {@code null}
     * @return 估算字节数
     */
    private static int approximateBytes(String base64Data) {
        if (base64Data == null) {
            return 0;
        }
        return base64Data.length() / 4 * 3;
    }
}
