package zcd.jellyfish.script;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import zcd.jellyfish.api.JellyfishException;

/**
 * 跨语言运行时的统一序列化入口。
 * <p>
 * <b>为什么不用内核的 {@code ObjectMapperWrapper}</b>：那个类在 {@code jellyfish-infra} 里，
 * 插件看不到它（插件只依赖 {@code jellyfish-api}）。这与 {@code jellyfish-plugin-todo} 自带 Jackson
 * 是同一处约束，因此这里自带一份，且配置对齐内核的口径：忽略未知字段、写全 {@code null} 字段。
 * <p>
 * <b>为什么忽略未知字段很重要</b>：脚本侧与内核侧是各自独立演进的，脚本多传一个字段、
 * 或内核删掉一个字段，都不该让整次调用失败——协议里的未知字段一律是「无害的多余信息」。
 * <p>
 * 统一在本类里配置 {@link ObjectMapper}，避免各处各自 {@code new ObjectMapper()} 导致行为不一致；
 * 序列化与反序列化失败都转成 {@link JellyfishException}，与内核的异常约定一致。
 *
 * @author zcd
 */
public final class ScriptJson {

    /** 全局唯一的 Jackson 映射器。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        // 序列化时写全字段（含值为 null 的字段）：协议字段的「缺省」与「显式为 null」表达同一语义，
        // 写全可以让抓包调试时一眼看出内核到底送了什么，而不是靠猜字段缺失的原因
        MAPPER.setDefaultPropertyInclusion(JsonInclude.Include.ALWAYS);

        // 反序列化时忽略未知字段：脚本与内核各自演进，多余字段不应让调用失败
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 工具类，禁止实例化。
     */
    private ScriptJson() {
    }

    /**
     * 将对象序列化为 JSON 字符串。
     *
     * @param value 待序列化对象，可为 {@code null}
     * @return JSON 字符串
     * @throws JellyfishException 序列化失败时抛出
     */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException | RuntimeException e) {
            throw new JellyfishException("脚本协议序列化失败: " + value, e);
        }
    }

    /**
     * 将 JSON 字符串反序列化为指定类型。
     *
     * @param json        JSON 字符串，不可为 {@code null}
     * @param targetClass 目标类型，不可为 {@code null}
     * @param <T>         目标类型
     * @return 反序列化结果
     * @throws JellyfishException 反序列化失败时抛出
     */
    public static <T> T read(String json, Class<T> targetClass) {
        try {
            return MAPPER.readValue(json, targetClass);
        } catch (JsonProcessingException | RuntimeException e) {
            throw new JellyfishException("脚本协议反序列化失败: " + targetClass.getName(), e);
        }
    }

    /**
     * 将 JSON 字符串反序列化为带泛型的类型。
     *
     * @param json          JSON 字符串，不可为 {@code null}
     * @param typeReference 泛型类型引用，不可为 {@code null}
     * @param <T>           目标类型
     * @return 反序列化结果
     * @throws JellyfishException 反序列化失败时抛出
     */
    public static <T> T read(String json, TypeReference<T> typeReference) {
        try {
            return MAPPER.readValue(json, typeReference);
        } catch (JsonProcessingException | RuntimeException e) {
            throw new JellyfishException("脚本协议反序列化失败: " + typeReference.getType(), e);
        }
    }

    /**
     * 将 JSON 字符串解析为树，供「先看形状再决定怎么解」的协议分发使用。
     *
     * @param json JSON 字符串，不可为 {@code null}
     * @return JSON 树根节点
     * @throws JellyfishException 解析失败时抛出
     */
    public static JsonNode tree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException | RuntimeException e) {
            throw new JellyfishException("脚本协议 JSON 解析失败", e);
        }
    }

    /**
     * 将对象转换为 JSON 树，供需要逐字段拼装的协议编解码使用。
     *
     * @param value 待转换对象，可为 {@code null}（等价于 JSON {@code null}）
     * @return JSON 树节点，保证非 {@code null}
     */
    public static JsonNode treeOf(Object value) {
        return MAPPER.valueToTree(value);
    }
}
