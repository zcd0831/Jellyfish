package zcd.jellyfish.infra.tooloutput;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import zcd.jellyfish.api.extension.ToolOutputSink;
import zcd.jellyfish.infra.config.ReactSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.config.ToolOutputSettings;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 工具输出中间件：把工具产出的任意对象变成「回灌给模型的文本」，并在超限时截断 + 落盘。
 * <p>
 * <b>为什么必须统一收口</b>：单个工具可以忘记限流、可以由第三方插件提供、可以是脚本动态注册的，
 * 内核无法假设它们都听话。这里是「工具产出的任意对象」到「回灌给模型的文本」之间的最后一米，
 * 把兜底放在这里，任何失控的工具都只会撑爆一次结果，而不会撑爆整个上下文。
 * <p>
 * <b>两个触发点，一份实现</b>：丢内容的截断有两处入口——本类的
 * {@link #limit(String, String, String, Object)}（工具返回了完整对象，事后发现超限）与
 * {@link #sink(String, String, String, Consumer)} 造出的捕获期 sink（内容还在产生时就已经超限）。
 * 预览切分（{@link ToolOutputPreview}）、信封（{@link ToolOutputEnvelope}）与落盘
 * （{@link ToolOutputStore}）的实现只有一份，两处共用——各写一遍必然漂移成「两条路径的阈值与
 * 提示不一样」。
 * <p>
 * <b>截断策略是「头 30% + 尾 70%」而不是只留头</b>：工具输出的结论往往在末尾（编译错误、
 * 测试失败、退出信息）。只留头会让模型看到「一切正常的前 90%」而错过真正的那一行。
 * <p>
 * <b>结构感知而非字符切割</b>：脚本工具返回的是 {@code Map}/{@code List}，序列化后是一段 JSON；
 * 从中间切开会得到非法 JSON，模型根本解析不了。因此这里区分两条路——字符串结果按行做头尾切分，
 * 结构化结果按 JSON 子树截断，两者都保证「回灌的仍是合法 JSON 信封」。
 * <p>
 * <b>截断必留恢复路径</b>：完整内容先落盘（{@link ToolOutputStore}），信封里带上路径与回查指引。
 * 模型据此知道「我看到的是预览、完整内容在磁盘上、该用什么工具接着看」。
 * <p>
 * <b>预算是算出来的，不是拍出来的</b>：信封自身的长度（工具名 + 路径 + 指引）会参与计算，
 * 渲染后仍超出预算时再按差额收缩一次，因此「回灌文本不超过 {@code maxToolOutputChars}」这条
 * 不变式在 Unicode 转义、超长路径等情况下依然成立。
 * <p>
 * 无状态，可安全复用。
 *
 * @author zcd
 */
@Singleton
public class ToolOutputLimiter {

    /** 数组截断时给哨兵元素预留的字符数。 */
    private static final int SENTINEL_RESERVE = 32;

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
     * 造一个输出捕获通道，交给无界输出的工具（命令行）边产生边写。
     * <p>
     * <b>为什么由本类造而不是让工具自己落盘</b>：插件只依赖 {@code jellyfish-api}，拿不到落盘存储；
     * 让插件自己写文件就会长出第二套目录、命名、清理与提示格式。由本类造出来的 sink 把这些知识
     * 全部留在内核里，工具只知道「往里写」。
     * <p>
     * 落盘上限在这里做钳制：一个结果文件不该把整个会话目录的预算吃干，因此
     * {@code spillMaxBytes} 不超过 {@code maxBytes}（后者为 {@code 0} 表示不清理预算时才不钳制）。
     *
     * @param sessionId  会话标识，可为 {@code null}
     * @param toolCallId 工具调用标识，可为 {@code null}
     * @param toolName   工具名，可为 {@code null}
     * @param onChunk    实时输出的旁路接收者，可为 {@code null}（表示不需要实时展示）
     * @return 捕获通道，保证非 {@code null}
     */
    public ToolOutputSink sink(String sessionId, String toolCallId, String toolName, Consumer<String> onChunk) {
        return new SpillCapturingSink(store, sessionId, toolCallId, toolName, maxChars(), spillMaxBytes(), onChunk);
    }

    /**
     * 处理纯文本结果：未超限原样返回，超限则按头尾截断并落盘。
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
        String path = store.store(sessionId, toolCallId, toolName, text, false);
        String hint = ToolOutputEnvelope.hint(path);
        int totalLines = countLines(text);
        int budget = ToolOutputEnvelope.previewBudget(maxChars, toolName, path, hint);
        String rendered = "";
        for (int round = 0; round < ToolOutputEnvelope.MAX_FIT_ROUNDS; round++) {
            ToolOutputEnvelope envelope = ToolOutputEnvelope.text(toolName, text.length(), totalLines, path, hint,
                    ToolOutputPreview.text(text, budget));
            rendered = envelope.render();
            int excess = rendered.length() - maxChars;
            if (excess <= 0 || budget <= 1) {
                return rendered;
            }
            // 估算与转义长度有出入（例如正文里全是换行与引号）时按实际差额再收缩一次
            budget = Math.max(1, budget - excess - ToolOutputEnvelope.FIT_SLACK);
        }
        return rendered;
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
        String hint = ToolOutputEnvelope.hint(path);
        int budget = ToolOutputEnvelope.previewBudget(maxChars, toolName, path, hint);
        String rendered = "";
        for (int round = 0; round < ToolOutputEnvelope.MAX_FIT_ROUNDS; round++) {
            ToolOutputEnvelope envelope = ToolOutputEnvelope.structured(toolName, json.length(), path, hint,
                    shrink(root, budget));
            rendered = envelope.render();
            int excess = rendered.length() - maxChars;
            if (excess <= 0 || budget <= 1) {
                return rendered;
            }
            budget = Math.max(1, budget - excess - ToolOutputEnvelope.FIT_SLACK);
        }
        return rendered;
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
     * 取本次生效的单个结果落盘上限。
     * <p>
     * 上限不超过会话级清理预算：一个结果文件不该把整个会话目录的预算吃干，否则「刚写的那个
     * 把自己之外的都挤掉」会让同一批结果里只剩下它一个。
     *
     * @return 字节上限；{@code 0} 表示不限制
     */
    private long spillMaxBytes() {
        ToolOutputSettings settings = runtimeConfig.getReactSettings().getToolOutput();
        long spill = settings.getSpillMaxBytes();
        long sessionBudget = settings.getMaxBytes();
        if (spill <= 0 || sessionBudget <= 0) {
            return spill;
        }
        return Math.min(spill, sessionBudget);
    }

    /**
     * 统计文本行数：{@code \n} 个数加一，空文本记 {@code 0}。
     *
     * @param text 文本
     * @return 行数
     */
    static int countLines(String text) {
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
     * 取不会切开代理对的截断位置。
     *
     * @param text  文本
     * @param index 期望截断位置
     * @return 修正后的位置
     */
    static int safeCut(String text, int index) {
        int cut = Math.max(0, Math.min(index, text.length()));
        if (cut > 0 && cut < text.length() && Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }
        return cut;
    }

    /**
     * 把 JSON 树收缩到预算内：数组保留「前缀 + 哨兵 + 后缀」，对象保留前缀，标量按字符截断。
     * <p>
     * <b>为什么数组是头尾而不是只留前缀</b>：数组常按时间序排列（日志、结果列表），尾部是最近的
     * 结果，与文本路径偏尾的理由同源。
     * <p>
     * <b>为什么中间要留一个哨兵元素</b>：只把前缀与后缀拼起来，模型会把两段当成一份连续的数据，
     * 从而得出「这个列表只有这些项、而且顺序就是这样」的错误结论。哨兵用一段显眼的文本元素
     * 把断裂标出来。它绝不能省——这是结构化路径上唯一能让「中间被省略」可见的东西。
     *
     * @param node   原始节点
     * @param budget 字符预算
     * @return 收缩后的节点，保证序列化长度不超过预算太多
     */
    static JsonNode shrink(JsonNode node, int budget) {
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
     * 收缩数组：前缀 + 哨兵元素 + 后缀。
     * <p>
     * 一个元素都装不下时退化成「收缩后的首元素 + 哨兵」，因为空数组会让模型以为结果本来就是空的。
     *
     * @param node   数组节点
     * @param budget 字符预算
     * @return 收缩后的数组
     */
    private static JsonNode shrinkArray(JsonNode node, int budget) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        if (node.size() == 0) {
            return result;
        }
        int usable = Math.max(1, budget - SENTINEL_RESERVE);
        int headLimit = ToolOutputPreview.headBudget(usable);
        int tailLimit = ToolOutputPreview.tailBudget(usable);
        int headCount = 0;
        int used = 0;
        while (headCount < node.size() && used + size(node.get(headCount)) + 1 <= headLimit) {
            used += size(node.get(headCount)) + 1;
            headCount++;
        }
        int tailCount = 0;
        int tailUsed = 0;
        while (headCount + tailCount < node.size()
                && tailUsed + size(node.get(node.size() - 1 - tailCount)) + 1 <= tailLimit) {
            tailUsed += size(node.get(node.size() - 1 - tailCount)) + 1;
            tailCount++;
        }
        if (headCount == 0 && tailCount == 0) {
            result.add(shrink(node.get(0), Math.max(1, usable - 1)));
            if (node.size() > 1) {
                result.add(TextNode.valueOf(omittedMarker(node.size() - 1)));
            }
            return result;
        }
        for (int i = 0; i < headCount; i++) {
            result.add(node.get(i));
        }
        int omitted = node.size() - headCount - tailCount;
        if (omitted > 0) {
            result.add(TextNode.valueOf(omittedMarker(omitted)));
        }
        for (int i = node.size() - tailCount; i < node.size(); i++) {
            result.add(node.get(i));
        }
        return result;
    }

    /**
     * 生成数组截断的哨兵文本。
     *
     * @param omitted 被省略的元素个数
     * @return 哨兵文本
     */
    private static String omittedMarker(int omitted) {
        return "… 省略 " + omitted + " 项 …";
    }

    /**
     * 取节点的序列化长度。
     *
     * @param node 节点
     * @return 字符数
     */
    private static int size(JsonNode node) {
        return node.toString().length();
    }

    /**
     * 收缩对象：顺序保留能装下的字段；一个都装不下时递归收缩首字段。
     * <p>
     * 对象保持「只留前缀」而不加哨兵：对象没有「尾部更重要」的一般理由，往键空间里插一个
     * 说明性字段反而会被模型当成真实数据。
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
