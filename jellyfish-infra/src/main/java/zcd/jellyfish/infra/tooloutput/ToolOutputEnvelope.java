package zcd.jellyfish.infra.tooloutput;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具结果截断信封：工具输出超限时回灌给模型的<b>唯一合法报文</b>。
 * <p>
 * <b>为什么需要它</b>：直接把文本从中间切开会有两种后果——结构化结果（脚本工具返回的 JSON）
 * 变成非法 JSON，模型根本解析不了；纯文本结果被悄悄切短，模型以为那就是全部。本类把
 * 「被截断了、原来多大、完整内容在哪、怎么回查」写成一份机器可解析、又对人可读的报文，
 * 渲染与解析共用同一份字段常量，避免两处各写一遍键名而漂移。
 * <p>
 * <b>预览一律是 JSON 值</b>：结构化结果是结构截断后的子树，纯文本结果是字符串节点。这样
 * 「信封就是一段合法 JSON」对两类输出都成立，模型在任何路径下都不会收到半截 JSON。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ToolOutputEnvelope {

    /** 固定为 {@code true}，用于识别「这是一份截断信封」。 */
    static final String FIELD_TRUNCATED = "_truncated";

    /** 工具名。 */
    static final String FIELD_TOOL = "_tool";

    /** 原始输出的字符数。 */
    static final String FIELD_TOTAL_CHARS = "_total_chars";

    /** 原始输出的行数；结构化结果记为 {@code 0}。 */
    static final String FIELD_TOTAL_LINES = "_total_lines";

    /** 完整内容的落盘路径；落盘失败时为 {@code null}。 */
    static final String FIELD_PATH = "_path";

    /** 给模型的恢复指引。 */
    static final String FIELD_HINT = "_hint";

    /** 预览：结构化结果是结构截断后的子树，纯文本结果是字符串节点。 */
    static final String FIELD_PREVIEW = "preview";

    /** 工具名。 */
    private final String toolName;

    /** 原始输出字符数。 */
    private final int totalChars;

    /** 原始输出行数；结构化结果为 {@code 0}。 */
    private final int totalLines;

    /** 完整内容的落盘路径，可为 {@code null}（落盘失败）。 */
    private final String path;

    /** 给模型的恢复指引。 */
    private final String hint;

    /** 预览节点。 */
    private final JsonNode preview;

    /**
     * 构造截断信封。
     *
     * @param toolName   工具名，不可为 {@code null}
     * @param totalChars 原始输出字符数，保证非负
     * @param totalLines 原始输出行数，保证非负
     * @param path       完整内容的落盘路径，可为 {@code null}
     * @param hint       恢复指引，不可为 {@code null}
     * @param preview    预览节点，不可为 {@code null}
     */
    private ToolOutputEnvelope(String toolName, int totalChars, int totalLines, String path, String hint,
                               JsonNode preview) {
        this.toolName = toolName;
        this.totalChars = totalChars;
        this.totalLines = totalLines;
        this.path = path;
        this.hint = hint;
        this.preview = preview;
    }

    /**
     * 构造纯文本结果的截断信封。
     *
     * @param toolName   工具名
     * @param totalChars 原始字符数
     * @param totalLines 原始行数
     * @param path       落盘路径，可为 {@code null}
     * @param hint       恢复指引
     * @param preview    截断后的预览文本
     * @return 截断信封
     */
    public static ToolOutputEnvelope text(String toolName, int totalChars, int totalLines, String path, String hint,
                                          String preview) {
        return new ToolOutputEnvelope(toolName, totalChars, totalLines, path, hint, TextNode.valueOf(preview));
    }

    /**
     * 构造结构化结果的截断信封。
     *
     * @param toolName   工具名
     * @param totalChars 原始序列化字符数
     * @param path       落盘路径，可为 {@code null}
     * @param hint       恢复指引
     * @param preview    结构截断后的预览子树
     * @return 截断信封
     */
    public static ToolOutputEnvelope structured(String toolName, int totalChars, String path, String hint,
                                                JsonNode preview) {
        return new ToolOutputEnvelope(toolName, totalChars, 0, path, hint, preview);
    }

    /**
     * 渲染成回灌文本。
     *
     * @return 一段合法 JSON 报文
     */
    public String render() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put(FIELD_TRUNCATED, Boolean.TRUE);
        payload.put(FIELD_TOOL, toolName);
        payload.put(FIELD_TOTAL_CHARS, totalChars);
        payload.put(FIELD_TOTAL_LINES, totalLines);
        payload.put(FIELD_PATH, path);
        payload.put(FIELD_HINT, hint);
        payload.put(FIELD_PREVIEW, preview);
        return ObjectMapperWrapper.writeValueAsString(payload);
    }

    /**
     * 把一段文本解析成截断信封。
     * <p>
     * <b>失败一律返回 {@code null} 而不抛异常</b>：本方法的调用点（上下文 aging、窗口裁剪）
     * 都在「组装一轮请求」的路径上，一条普通工具结果不是信封是常态，抛异常会把常态做成故障。
     *
     * @param content 待解析文本，可为 {@code null}
     * @return 截断信封；不是信封或解析失败时返回 {@code null}
     */
    public static ToolOutputEnvelope parse(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '{') {
            return null;
        }
        JsonNode node;
        try {
            node = ObjectMapperWrapper.readTree(trimmed);
        } catch (RuntimeException e) {
            return null;
        }
        if (node == null || !node.isObject() || !node.path(FIELD_TRUNCATED).asBoolean(false)) {
            return null;
        }
        JsonNode preview = node.get(FIELD_PREVIEW);
        if (preview == null) {
            return null;
        }
        return new ToolOutputEnvelope(node.path(FIELD_TOOL).asText(""),
                node.path(FIELD_TOTAL_CHARS).asInt(0),
                node.path(FIELD_TOTAL_LINES).asInt(0),
                node.path(FIELD_PATH).isTextual() ? node.path(FIELD_PATH).asText() : null,
                node.path(FIELD_HINT).asText(""), preview);
    }

    /**
     * 生成上下文里替换整条结果的短占位。
     * <p>
     * <b>保留路径是重点</b>：模型只要知道完整内容在哪，就仍能按需回查；把路径也省掉，
     * 一条旧结果就真的只剩「曾经有个结果」这一句话了。
     *
     * @return 一行 stub 文本
     */
    public String stub() {
        StringBuilder text = new StringBuilder("[工具结果已省略] tool=").append(toolName)
                .append(" 原始 ").append(totalChars).append(" 字符");
        if (path == null) {
            text.append("，且落盘失败，内容已不可恢复");
        } else {
            text.append("，完整内容：").append(path).append("（需细节用 read_file 或 grep_files 回查该文件）");
        }
        return text.toString();
    }

    /**
     * 获取工具名。
     *
     * @return 工具名
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * 获取原始输出字符数。
     *
     * @return 字符数，保证非负
     */
    public int getTotalChars() {
        return totalChars;
    }

    /**
     * 获取原始输出行数；结构化结果为 {@code 0}。
     *
     * @return 行数，保证非负
     */
    public int getTotalLines() {
        return totalLines;
    }

    /**
     * 获取完整内容的落盘路径。
     *
     * @return 绝对路径；落盘失败时为 {@code null}
     */
    public String getPath() {
        return path;
    }

    /**
     * 获取预览节点。
     *
     * @return 预览节点，保证非 {@code null}
     */
    public JsonNode getPreview() {
        return preview;
    }
}
