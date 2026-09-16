package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import zcd.jellyfish.api.extension.CommandChoice;
import zcd.jellyfish.script.ScriptJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 协议载荷的读写小工具：把「从节点里安全取值」与「候选清单的双向转换」收在一处。
 * <p>
 * <b>为什么取值要「安全」而不是直接 {@code node.get(x).asText()}</b>：脚本侧给的是外部输入，
 * 缺字段、类型不对、值是多层嵌套都会真实发生。逐处写 {@code isTextual()} 判断会把 codec
 * 淹在样板里，写漏一处就是一次 {@code NullPointerException}——而它出现在同步派发路径上，
 * 表现是「插件抛异常」而不是「参数没给对」，排查方向完全被带偏。
 * <p>
 * <b>候选清单为什么单独抽出来</b>：{@code command} 的结果里可以带候选，{@code command_options}
 * 的结果就是一个候选清单。两处各写一份解析，迟早会漂移成「执行时能选、单独查候选时选不了」
 * 这种只在特定路径上复现的毛病。
 * <p>
 * 工具类，禁止实例化。
 *
 * @author zcd
 */
final class Payloads {

    /** 候选的取值字段名，同时是唯一的必填字段。 */
    private static final String FIELD_VALUE = "value";

    /** 候选的显示文本字段名。 */
    private static final String FIELD_LABEL = "label";

    /** 候选的补充说明字段名。 */
    private static final String FIELD_DESCRIPTION = "description";

    /** 候选的是否当前取值字段名。 */
    private static final String FIELD_CURRENT = "current";

    /**
     * 工具类，禁止实例化。
     */
    private Payloads() {
    }

    /**
     * 取文本字段，非文本或缺失一律返回 {@code null}。
     *
     * @param node  对象节点，可为 {@code null}
     * @param field 字段名
     * @return 文本值或 {@code null}
     */
    static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }

    /**
     * 取可空整数字段，非数字或缺失一律返回 {@code null}。
     * <p>
     * 返回 {@code null} 而不是 0 是刻意的：这些字段在协议里是「本插件不表态」的表达，
     * 归一成 0 会变成「明确要求保留 0 条」，语义正好相反。
     *
     * @param node  对象节点，可为 {@code null}
     * @param field 字段名
     * @return 整数值或 {@code null}
     */
    static Integer integer(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? null : value.asInt();
    }

    /**
     * 取布尔字段，非布尔或缺失一律返回 {@code false}。
     *
     * @param node         对象节点，可为 {@code null}
     * @param field        字段名
     * @param defaultValue 缺失时的取值
     * @return 布尔值
     */
    static boolean bool(JsonNode node, String field, boolean defaultValue) {
        if (node == null || !node.isObject()) {
            return defaultValue;
        }
        JsonNode value = node.get(field);
        return value == null || !value.isBoolean() ? defaultValue : value.asBoolean();
    }

    /**
     * 解析候选数组。
     * <p>
     * 宽容处理 {@code null}、非数组与数组中的 {@code null} 元素：候选只是输入辅助，
     * 让一个写坏的候选项把整条命令打挂不值得。但 {@code value} 缺失不在此列——它是选中后要追加到
     * 命令名之后的原文，缺了就是彻底的无效项，直接跳过（剩余候选仍能正常显示）。
     *
     * @param node 候选数组节点，可为 {@code null}
     * @return 候选清单，保证非 {@code null}
     */
    static List<CommandChoice> choices(JsonNode node) {
        List<CommandChoice> choices = new ArrayList<CommandChoice>();
        if (node == null || !node.isArray()) {
            return choices;
        }
        for (JsonNode element : node) {
            if (element == null || !element.isObject()) {
                continue;
            }
            String value = text(element, FIELD_VALUE);
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            choices.add(new CommandChoice(value, text(element, FIELD_LABEL),
                    text(element, FIELD_DESCRIPTION), bool(element, FIELD_CURRENT, false)));
        }
        return choices;
    }

    /**
     * 写出候选数组。
     *
     * @param choices 候选清单，可为 {@code null} 或空
     * @return 候选数组节点，保证非 {@code null}
     */
    static ArrayNode writeChoices(List<CommandChoice> choices) {
        ArrayNode array = ScriptJson.arrayNode();
        if (choices == null) {
            return array;
        }
        for (CommandChoice choice : choices) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put(FIELD_VALUE, choice.getValue());
            payload.put(FIELD_LABEL, choice.getLabel());
            payload.put(FIELD_DESCRIPTION, choice.getDescription());
            payload.put(FIELD_CURRENT, choice.isCurrent());
            array.add(ScriptJson.treeOf(payload));
        }
        return array;
    }
}
