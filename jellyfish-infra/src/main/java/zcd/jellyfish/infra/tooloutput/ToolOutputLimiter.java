package zcd.jellyfish.infra.tooloutput;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import zcd.jellyfish.infra.config.ReactSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Iterator;
import java.util.Map;

/**
 * 工具输出中间件：所有工具结果回灌模型前的<b>唯一硬截断点</b>。
 * <p>
 * <b>为什么必须在这里统一收口</b>：单个工具可以忘记限流、可以由第三方插件提供、可以是脚本
 * 动态注册的，内核无法假设它们都听话。这里是「工具产出的任意对象」到「回灌给模型的文本」之间
 * 的最后一米，把兜底放在这里，任何失控的工具都只会撑爆一次结果，而不会撑爆整个上下文。
 * <p>
 * <b>结构感知而非字符切割</b>：脚本工具返回的是 {@code Map}/{@code List}，序列化后是一段 JSON；
 * 从中间切开会得到非法 JSON，模型解析不了。因此这里区分两条路——字符串结果按行截断，
 * 结构化结果按 JSON 子树截断，两者都保证「回灌的仍是合法 JSON 信封」。
 * <p>
 * <b>截断必留恢复路径</b>：完整内容先落盘（{@link ToolOutputStore}），信封里带上路径与回查指引。
 * 模型据此知道「我看到的是预览、完整内容在磁盘上、该用什么工具接着看」。
 * <p>
 * 无状态，可安全复用。
 *
 * @author zcd
 */
@Singleton
public class ToolOutputLimiter {

    /** 信封元数据（工具名 / 总量 / 路径 / 指引）预留的字符数。 */
    private static final int ENVELOPE_OVERHEAD_CHARS = 512;

    /** 落盘路径不可用时的恢复指引。 */
    private static final String HINT_WITHOUT_PATH =
            "本轮只保留预览，且完整内容落盘失败（磁盘不可写）；请缩小查询范围后重试。";

    /** 运行时配置门面：截断上限现读，热更新后立刻生效。 */
    private final RuntimeConfig runtimeConfig;

    /** 完整内容落盘存储。 */
    private final ToolOutputStore store;

    /**
     * 构造工具输出中间件。
     *
     * @param runtimeConfig 运行时配置门面，不可为 {@code null}
     * @param store         完整内容落盘存储，不可为 {@code null}
     */
    @Inject
    public ToolOutputLimiter(RuntimeConfig runtimeConfig, ToolOutputStore store) {
        this.runtimeConfig = runtimeConfig;
        this.store = store;
    }

    /**
     * 把工具结果规范成回灌给模型的文本：字符串原样、结构化结果序列化、超限则截断并落盘。
     *
     * @param sessionId  会话标识，可为 {@code null}
     * @param toolCallId 工具调用标识，可为 {@code null}
     * @param toolName   工具名，可为 {@code null}
     * @param output     工具原始输出，可为 {@code null}
     * @return 回灌文本，保证非 {@code null}
     */
    public String limit(String sessionId, String toolCallId, String toolName, Object output) {
        if (output == null) {
            return "";
        }
        if (output instanceof String) {
            return limitText(sessionId, toolCallId, toolName, (String) output);
        }
        return limitStructured(sessionId, toolCallId, toolName, output);
    }

    /**
     * 处理纯文本结果：未超限原样返回，超限则按行截断并落盘。
     *
     * @param sessionId  会话标识
     * @param toolCallId 工具调用标识
     * @param toolName   工具名
     * @param text       文本结果
     * @return 回灌文本
     */
    private String limitText(String sessionId, String toolCallId, String toolName, String text) {
        int maxChars = maxChars();
        if (text.length() <= maxChars) {
            return text;
        }
        int previewBudget = previewBudget(maxChars);
        String path = store.store(sessionId, toolCallId, toolName, text, false);
        ToolOutputEnvelope envelope = ToolOutputEnvelope.text(toolName, text.length(), countLines(text), path,
                hint(path), headByLines(text, previewBudget));
        return envelope.render();
    }

    /**
     * 处理结构化结果：序列化后未超限原样返回，超限则按 JSON 子树截断并落盘。
     *
     * @param sessionId  会话标识
     * @param toolCallId 工具调用标识
     * @param toolName   工具名
     * @param output     结构化输出对象
     * @return 回灌文本，保证是一段合法 JSON
     */
    private String limitStructured(String sessionId, String toolCallId, String toolName, Object output) {
        String json = ObjectMapperWrapper.writeValueAsString(output);
        int maxChars = maxChars();
        if (json.length() <= maxChars) {
            return json;
        }
        JsonNode root;
        try {
            root = ObjectMapperWrapper.readTree(json);
        } catch (RuntimeException e) {
            // 自己刚序列化出来的文本解析不了，只可能是序列化配置出了问题；退化成文本路径，别把结果弄丢
            return limitText(sessionId, toolCallId, toolName, json);
        }
        String path = store.store(sessionId, toolCallId, toolName, json, true);
        ToolOutputEnvelope envelope = ToolOutputEnvelope.structured(toolName, json.length(), path, hint(path),
                shrink(root, previewBudget(maxChars)));
        return envelope.render();
    }

    /**
     * 取本次生效的输出字符上限。
     *
     * @return 字符数上限，保证为正
     */
    private int maxChars() {
        ReactSettings settings = runtimeConfig.getReactSettings();
        int maxChars = settings.getMaxToolOutputChars();
        return maxChars > 0 ? maxChars : ReactSettings.DEFAULT_MAX_TOOL_OUTPUT_CHARS;
    }

    /**
     * 计算预览可用的字符预算：总上限扣掉信封元数据。
     *
     * @param maxChars 总字符上限
     * @return 预览预算，保证至少 1
     */
    private static int previewBudget(int maxChars) {
        return Math.max(1, maxChars - ENVELOPE_OVERHEAD_CHARS);
    }

    /**
     * 组装落盘成功与否的恢复指引。
     *
     * @param path 落盘路径，可为 {@code null}
     * @return 指引文本
     */
    private static String hint(String path) {
        if (path == null) {
            return HINT_WITHOUT_PATH;
        }
        return "本轮只保留预览。完整内容已落盘：" + path
                + "；需要细节时用 read_file（支持 offset/limit）或 grep_files 回查该文件。";
    }

    /**
     * 按行取文本头部：优先在换行处断开，避免把一行切成两半看不出是同一行。
     *
     * @param text   原文
     * @param budget 字符预算
     * @return 预览文本
     */
    private static String headByLines(String text, int budget) {
        if (text.length() <= budget) {
            return text;
        }
        int end = safeCut(text, budget);
        int newline = text.lastIndexOf('\n', end - 1);
        // 断点太靠前（< 预算一半）就宁可按字符切，避免一个超长首行把预览压成一小截
        int cut = newline >= budget / 2 ? newline : end;
        return text.substring(0, safeCut(text, cut));
    }

    /**
     * 取不会切开代理对的截断位置。
     *
     * @param text   原文
     * @param index  期望截断位置
     * @return 修正后的位置
     */
    private static int safeCut(String text, int index) {
        int cut = Math.max(0, Math.min(index, text.length()));
        if (cut > 0 && cut < text.length() && Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }
        return cut;
    }

    /**
     * 统计文本行数：{@code \n} 个数加一，空文本记 {@code 0}。
     *
     * @param text 文本
     * @return 行数
     */
    private static int countLines(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /**
     * 把 JSON 树收缩到预算内：数组与对象按顺序保留前缀，标量按字符截断。
     * <p>
     * <b>为什么不做深度优先的「平均分配」</b>：模型最常用的入口是「数组的前若干项」「对象的前若干键」，
     * 顺序前缀既好实现又便于它继续按偏移回查；均分预算会切出一堆残缺的中间结构，反而更难用。
     *
     * @param node   原始节点
     * @param budget 字符预算
     * @return 收缩后的节点，保证序列化长度不超过预算太多
     */
    private static JsonNode shrink(JsonNode node, int budget) {
        if (node == null || node.isNull()) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (node.toString().length() <= budget) {
            return node;
        }
        if (node.isArray()) {
            return shrinkArray(node, budget);
        }
        if (node.isObject()) {
            return shrinkObject(node, budget);
        }
        String text = node.isTextual() ? node.asText() : node.toString();
        return TextNode.valueOf(text.substring(0, safeCut(text, Math.max(1, budget - 2))));
    }

    /**
     * 收缩数组：顺序保留能装下的元素；一个都装不下时递归收缩首元素。
     *
     * @param node   数组节点
     * @param budget 字符预算
     * @return 收缩后的数组
     */
    private static JsonNode shrinkArray(JsonNode node, int budget) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        Iterator<JsonNode> children = node.elements();
        while (children.hasNext()) {
            JsonNode child = children.next();
            if (result.size() > 0 && result.toString().length() + child.toString().length() + 1 > budget) {
                break;
            }
            result.add(child);
        }
        if (result.size() == 0 && node.size() > 0) {
            result.add(shrink(node.get(0), Math.max(1, budget - 2)));
        }
        return result;
    }

    /**
     * 收缩对象：顺序保留能装下的字段；一个都装不下时递归收缩首字段。
     *
     * @param node   对象节点
     * @param budget 字符预算
     * @return 收缩后的对象
     */
    private static JsonNode shrinkObject(JsonNode node, int budget) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            result.set(field.getKey(), field.getValue());
            if (result.toString().length() > budget) {
                result.remove(field.getKey());
                break;
            }
        }
        if (result.size() == 0 && node.size() > 0) {
            Map.Entry<String, JsonNode> first = node.fields().next();
            int childBudget = Math.max(1, budget - first.getKey().length() - 4);
            result.set(first.getKey(), shrink(first.getValue(), childBudget));
        }
        return result;
    }
}
