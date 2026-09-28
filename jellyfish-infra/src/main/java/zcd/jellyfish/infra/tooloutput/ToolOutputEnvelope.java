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

    /**
     * 落盘内容是否不完整。
     * <p>
     * 只在「捕获期溢出且触及落盘上限」时为 {@code true}：此时路径下只有前一段内容，
     * 信封必须把这件事说出来，否则「完整内容在 path」就是一句假话。
     */
    static final String FIELD_PARTIAL = "_partial";

    /** 落盘路径不可用时的恢复指引。 */
    private static final String HINT_WITHOUT_PATH =
            "本轮只保留预览，且完整内容落盘失败（磁盘不可写）；请缩小查询范围后重试。";

    /**
     * 信封骨架（字段名与结构字符）的字符数估计。
     * <p>
     * 只是估计：真实的转义长度（正文里的换行、引号、非 ASCII）与它总有出入，因此两条调用路径
     * 都在渲染后校正一次（见 {@link #MAX_FIT_ROUNDS}）。
     */
    static final int SKELETON_CHARS = 120;

    /** 渲染后仍超预算时最多校正多少轮；每轮至少收缩「超出量 + 余量」，因此收敛很快。 */
    static final int MAX_FIT_ROUNDS = 8;

    /** 每轮收缩的额外余量，避免因估算误差反复打转。 */
    static final int FIT_SLACK = 64;

    /**
     * 计算预览可用的字符预算：总上限扣掉信封骨架、工具名、落盘路径与恢复指引。
     * <p>
     * 放在本类而不是两条调用路径各自算：算「本信封自己有多长」属于信封的语义，
     * 而「预算怎么切给头尾」才是 {@link ToolOutputPreview} 的事。
     * <p>
     * <b>退化情形</b>：路径与指引本身就长过总上限时（把 {@code maxToolOutputChars} 配得极小即可复现），
     * 本方法返回 1，信封长度会超出上限。此时以「保住恢复路径与指引」为先——它们是模型能否自己找回
     * 完整内容的唯一依据，而预览已经缩到没有信息量了。
     *
     * @param maxChars 回灌文本的总字符上限
     * @param toolName 工具名，可为 {@code null}
     * @param path     落盘路径，可为 {@code null}
     * @param hint     恢复指引，可为 {@code null}
     * @return 预览预算，保证至少 1
     */
    static int previewBudget(int maxChars, String toolName, String path, String hint) {
        int overhead = SKELETON_CHARS + length(toolName) + length(path) + length(hint);
        return Math.max(1, maxChars - overhead);
    }

    /**
     * 取字符串长度，{@code null} 记 0。
     *
     * @param text 文本，可为 {@code null}
     * @return 字符数
     */
    private static int length(String text) {
        return text == null ? 0 : text.length();
    }

    /**
     * 组装落盘成功与否的恢复指引。
     * <p>
     * 放在本类而不是调用点：指引描述的是「信封里的路径怎么用」，属于信封自己的语义。
     * 事后截断与捕获期溢出两条路径共用它，否则两份措辞迟早会漂移。
     *
     * @param path 落盘路径，可为 {@code null}
     * @return 指引文本，保证非 {@code null}
     */
    public static String hint(String path) {
        return hint(path, false);
    }

    /**
     * 组装落盘成功与否的恢复指引。
     *
     * @param path    落盘路径，可为 {@code null}
     * @param partial 落盘内容是否不完整
     * @return 指引文本，保证非 {@code null}
     */
    public static String hint(String path, boolean partial) {
        if (path == null) {
            return HINT_WITHOUT_PATH;
        }
        if (partial) {
            return "本轮只保留预览（开头与结尾），但输出超过落盘上限，文件里只有前面一部分：" + path
                    + "；超出部分未捕获、不可恢复。";
        }
        return "本轮只保留预览（开头与结尾）。完整内容已落盘：" + path
                + "；用 read_file（支持 offset/limit）或 grep_files 回查。";
    }

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

    /** 落盘内容是否不完整（捕获期溢出触及落盘上限）。 */
    private final boolean partial;

    /**
     * 构造截断信封。
     *
     * @param toolName   工具名，不可为 {@code null}
     * @param totalChars 原始输出字符数，保证非负
     * @param totalLines 原始输出行数，保证非负
     * @param path       完整内容的落盘路径，可为 {@code null}
     * @param hint       恢复指引，不可为 {@code null}
     * @param preview    预览节点，不可为 {@code null}
     * @param partial    落盘内容是否不完整
     */
    private ToolOutputEnvelope(String toolName, int totalChars, int totalLines, String path, String hint,
                               JsonNode preview, boolean partial) {
        this.toolName = toolName;
        this.totalChars = totalChars;
        this.totalLines = totalLines;
        this.path = path;
        this.hint = hint;
        this.preview = preview;
        this.partial = partial;
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
        return text(toolName, totalChars, totalLines, path, hint, preview, false);
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
     * @param partial    落盘内容是否不完整
     * @return 截断信封
     */
    public static ToolOutputEnvelope text(String toolName, int totalChars, int totalLines, String path, String hint,
                                          String preview, boolean partial) {
        return new ToolOutputEnvelope(toolName, totalChars, totalLines, path, hint, TextNode.valueOf(preview),
                partial);
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
        return structured(toolName, totalChars, path, hint, preview, false);
    }

    /**
     * 构造结构化结果的截断信封。
     *
     * @param toolName   工具名
     * @param totalChars 原始序列化字符数
     * @param path       落盘路径，可为 {@code null}
     * @param hint       恢复指引
     * @param preview    结构截断后的预览子树
     * @param partial    落盘内容是否不完整
     * @return 截断信封
     */
    public static ToolOutputEnvelope structured(String toolName, int totalChars, String path, String hint,
                                                JsonNode preview, boolean partial) {
        return new ToolOutputEnvelope(toolName, totalChars, 0, path, hint, preview, partial);
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
        payload.put(FIELD_PARTIAL, Boolean.valueOf(partial));
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
                node.path(FIELD_HINT).asText(""), preview, node.path(FIELD_PARTIAL).asBoolean(false));
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
        } else if (partial) {
            text.append("，且落盘内容不完整（超出落盘上限的部分未捕获）：").append(path);
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
     * 判断落盘内容是否不完整。
     *
     * @return 路径存在但只有前一段内容时返回 {@code true}
     */
    public boolean isPartial() {
        return partial;
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
