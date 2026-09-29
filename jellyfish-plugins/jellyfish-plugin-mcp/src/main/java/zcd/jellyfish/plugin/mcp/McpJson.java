package zcd.jellyfish.plugin.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 本插件内部的 JSON 入口：把 Jackson 的用法收在一处，并给出几个协议里反复出现的取值帮助方法。
 * <p>
 * <b>为什么不用内核的 {@code ObjectMapperWrapper}</b>：那个类在 {@code jellyfish-infra} 里，
 * 插件看不到它（插件只依赖 {@code jellyfish-api}），因此自带一份，配置与 {@code jellyfish-plugin-todo}
 * 的自带份同口径。
 * <p>
 * <b>为什么这里的「取字段」全都容忍缺字段与错类型</b>：对面是另一个进程、另一个实现，
 * 甚至是另一个版本的实现。字段缺失是一个正常的协议状态（很多字段本来就是可选的），
 * 把它当成错误会让「这个 server 少写了一个 title」升级成「这个 server 完全用不了」。
 * 真正不可缺的只有 {@code result.tools} 这类结构性字段，那由调用点自己判。
 * <p>
 * <b>不打印正文</b>：这些对象里可能有工具返回值，混进日志行会很难看，也没有必要。
 * <p>
 * 不能实例化。
 *
 * @author zcd
 */
final class McpJson {

    /** 全局唯一的映射器；本插件没有需要定制序列化行为的地方。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 工具类，禁止实例化。
     */
    private McpJson() {
    }

    /**
     * 新建一个空对象节点。
     *
     * @return 对象节点
     */
    static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    /**
     * 把任意可序列化的值转成节点，用于把工具参数放进请求。
     *
     * @param value 任意值，可为 {@code null}
     * @return 节点；无法序列化时返回 {@code null}（宁可少传一个参数，也不要让整次调用失败）
     */
    static JsonNode valueToTree(Object value) {
        if (value == null) {
            return MAPPER.nullNode();
        }
        try {
            return MAPPER.valueToTree(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 解析 JSON 文本。
     *
     * @param json JSON 文本，不可为 {@code null}
     * @return 根节点
     * @throws JellyfishException 文本不是合法 JSON 时抛出
     */
    static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException | RuntimeException e) {
            throw new JellyfishException("MCP 消息不是合法 JSON: " + abbreviate(json), e);
        }
    }

    /**
     * 解析 JSON 文本并要求根节点是对象。
     * <p>
     * 协议里的每条消息都必须是对象，因此这是一个比「是不是 JSON」更强的判据：
     * 一个数组或标量到了这里，说明对面根本不是 MCP server，早点报出来比后面到处判空好。
     *
     * @param json JSON 文本，不可为 {@code null}
     * @return 对象节点
     * @throws JellyfishException 文本非法或根节点不是对象时抛出
     */
    static ObjectNode parseObject(String json) {
        JsonNode root = parse(json);
        if (!root.isObject()) {
            throw new JellyfishException("MCP 消息的根节点必须是对象: " + abbreviate(json));
        }
        return (ObjectNode) root;
    }

    /**
     * 序列化。
     *
     * @param node 节点，不可为 {@code null}
     * @return JSON 文本（单行，不含换行——协议要求消息内部不得出现换行）
     * @throws JellyfishException 序列化失败时抛出
     */
    static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException | RuntimeException e) {
            throw new JellyfishException("MCP 消息序列化失败", e);
        }
    }

    /**
     * 读取字符串字段。
     *
     * @param node     节点，可为 {@code null}
     * @param field    字段名
     * @param fallback 缺省值
     * @return 字段值；缺失、为 null 或不是字符串时返回缺省值
     */
    static String text(JsonNode node, String field, String fallback) {
        if (node == null) {
            return fallback;
        }
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : fallback;
    }

    /**
     * 读取布尔字段。
     *
     * @param node     节点，可为 {@code null}
     * @param field    字段名
     * @param fallback 缺省值
     * @return 字段值；缺失或不是布尔时返回缺省值
     */
    static boolean bool(JsonNode node, String field, boolean fallback) {
        if (node == null) {
            return fallback;
        }
        JsonNode value = node.get(field);
        return value != null && value.isBoolean() ? value.asBoolean() : fallback;
    }

    /**
     * 读取整数字段。
     *
     * @param node     节点，可为 {@code null}
     * @param field    字段名
     * @param fallback 缺省值
     * @return 字段值；缺失或不是数字时返回缺省值
     */
    static int intOf(JsonNode node, String field, int fallback) {
        if (node == null) {
            return fallback;
        }
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asInt() : fallback;
    }

    /**
     * 读取字符串数组字段。
     *
     * @param node  节点，可为 {@code null}
     * @param field 字段名
     * @return 不可变字符串列表；缺失或不是数组时返回空列表，非字符串元素被跳过
     */
    static List<String> stringList(JsonNode node, String field) {
        if (node == null) {
            return Collections.emptyList();
        }
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>();
        for (JsonNode item : (ArrayNode) value) {
            if (item.isTextual()) {
                result.add(item.asText());
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 取一个子对象。
     *
     * @param node  节点，可为 {@code null}
     * @param field 字段名
     * @return 子节点；缺失或不是对象时返回 {@code null}
     */
    static JsonNode childObject(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isObject() ? value : null;
    }

    /**
     * 取一个子数组。
     *
     * @param node  节点，可为 {@code null}
     * @param field 字段名
     * @return 子节点；缺失或不是数组时返回 {@code null}
     */
    static JsonNode childArray(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isArray() ? value : null;
    }

    /**
     * 把节点转成普通 Java 值，供内核原样下发给厂商。
     * <p>
     * <b>转不了就给字符串而不是丢字段</b>：一个参数定义变得难看，比「工具少了一个参数」
     * 更容易发现与修正。
     *
     * @param node 节点，可为 {@code null}
     * @return 普通值（Map / List / String / Number / Boolean）；{@code null} 节点返回 {@code null}
     */
    static Object toPlainValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return MAPPER.convertValue(node, Object.class);
        } catch (RuntimeException e) {
            return node.toString();
        }
    }

    /**
     * 把文本压成单行并截断，只用于日志与错误信息。
     *
     * @param text 原始文本，可为 {@code null}
     * @return 截断后的文本
     */
    static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String collapsed = text.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= 200 ? collapsed : collapsed.substring(0, 200) + "…";
    }
}
