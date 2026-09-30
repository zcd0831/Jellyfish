package zcd.jellyfish.infra.support;

import com.fasterxml.jackson.core.type.TypeReference;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具调用参数的展示文本：把模型给的参数渲染成「给人看的」一段文本。
 * <p>
 * <b>为什么必须收在一处</b>：同一条参数有三个显示面——TUI 的审批浮层、TUI 的轨迹行、
 * {@code -cli} 的 {@code --show-tool-args} 诊断行。它们必须给出同一份文本：
 * 「这条命令长什么样」如果因界面而异，用户在同一件事上会看到两种说法，排查时也就无从对账。
 * <p>
 * <b>为什么不脱敏（与 Codex CLI 的 {@code --verbose} 同一口径）</b>：外壳并不知道某个参数是不是密钥，
 * 按名字猜（{@code apiKey} / token / secret …）两头都不准——它遮不住真正会出事的地方
 * （{@code shell} 的 {@code command}、{@code write_file} 的 {@code content}，密钥正是从这里进出的），
 * 却会给出「它替我打过码了」这种错误的安全感。所以这里照原样显示，把「本次调用的参数里可能有敏感信息」
 * 当作使用者要自己知道的前提（逃生门就是默认不打：{@code -cli} 要显式开旗标，TUI 折叠态只占几行）。
 * <b>要遮蔽也只能由工具或用户显式声明</b>，而不是由外壳按参数名猜——外壳这一层永远不做。
 * <p>
 * <b>为什么换行默认保留、另有 {@link #singleLine(Map)}</b>：参数里的换行是字符串值的一部分
 * （heredoc、多行正文）。审批浮层靠它把一段命令按原样铺开；而轨迹行与 {@code -cli} 的一行诊断
 * 需要「一行就是一条记录」，由调用方显式选择压平，而不是让默认行为替它决定。
 * <p>
 * 不可变、无状态，可安全跨线程调用。
 *
 * @author zcd
 */
public final class ToolArgumentsText {

    /** 参数为空时的占位文本。 */
    public static final String NONE = "-";

    /**
     * 工具类，禁止实例化。
     */
    private ToolArgumentsText() {
    }

    /**
     * 把参数映射渲染成紧凑的 JSON 形态文本。
     * <p>
     * 键与值都过一遍控制字符过滤：它们来自模型，可能带 {@code ESC} 这类会改写屏幕的字符。
     * 换行保留（见类注释）；值照原样显示，不做脱敏。
     *
     * @param arguments 参数映射，可为 {@code null}
     * @return 展示文本；参数为空时返回 {@link #NONE}，保证非 {@code null}
     */
    public static String text(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return NONE;
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append('"').append(ControlChars.strip(entry.getKey())).append("\": ");
            sb.append(valueOf(entry.getValue()));
        }
        return sb.append('}').toString();
    }

    /**
     * 同 {@link #text(Map)}，但压成一行：连续的空白（含换行）折叠成一个空格。
     * <p>
     * 给「一行就是一条记录」的显示面用（TUI 轨迹行、{@code -cli} 诊断行）。压平之后
     * 参数占几行只由宽度与调用方自己的上限决定，不会被参数内的换行牵着走。
     *
     * @param arguments 参数映射，可为 {@code null}
     * @return 单行展示文本；参数为空时返回空串，保证非 {@code null}
     */
    public static String singleLine(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        return collapse(text(arguments));
    }

    /**
     * 从参数 JSON 原文渲染单行展示文本。
     * <p>
     * 会话里存的 {@code toolCalls.arguments} 是模型给的原始 JSON 串，而显示面要的是解析后的文本；
     * 解析失败（流式残留、模型写坏的 JSON）时退回原文——显示原始串比什么都不显示好。
     * 两条路径都过一遍控制字符过滤。
     *
     * @param argumentsJson 参数 JSON 原文，可为 {@code null}
     * @return 单行展示文本；没有可用参数时返回空串，保证非 {@code null}
     */
    public static String singleLineFromJson(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.trim().isEmpty()) {
            return "";
        }
        try {
            Map<String, Object> parsed = ObjectMapperWrapper.readValue(argumentsJson,
                    new TypeReference<LinkedHashMap<String, Object>>() { });
            if (parsed == null || parsed.isEmpty()) {
                return "";
            }
            return singleLine(parsed);
        } catch (RuntimeException ignored) {
            return collapse(ControlChars.strip(argumentsJson));
        }
    }

    /**
     * 把文本压成单行：连续的空白（含换行）折叠成一个空格。
     *
     * @param text 原始文本，可为 {@code null}
     * @return 单行文本，保证非 {@code null}
     */
    public static String collapse(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        boolean pendingSpace = false;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                pendingSpace = true;
                continue;
            }
            if (pendingSpace && sb.length() > 0) {
                sb.append(' ');
            }
            pendingSpace = false;
            sb.appendCodePoint(codePoint);
        }
        return sb.toString();
    }

    /**
     * 渲染单个参数值：字符串加引号，其余取 {@code String.valueOf}。
     *
     * @param value 参数值，可为 {@code null}
     * @return 展示文本，保证非 {@code null}
     */
    private static String valueOf(Object value) {
        if (value == null) {
            return "null";
        }
        String rendered = ControlChars.strip(String.valueOf(value));
        return value instanceof String ? "\"" + rendered + "\"" : rendered;
    }
}
