package zcd.jellyfish.infra.support;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import zcd.jellyfish.api.JellyfishException;

/**
 * 全局唯一的 Jackson 序列化入口。
 * <p>
 * 统一在此处配置 {@link ObjectMapper}，避免各模块各自 {@code new ObjectMapper()} 导致行为不一致；
 * 反序列化忽略未知字段，便于配置文件向前兼容。
 *
 * @author zcd
 */
public final class ObjectMapperWrapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        // 序列化的时候写所有字段（包括值为 null 的字段）
        MAPPER.setDefaultPropertyInclusion(JsonInclude.Include.ALWAYS);

        // 反序列化的时候，如果 json 中存在类里没有的字段，不报错
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // 序列化的时候，如果对象为空，不报错
        MAPPER.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    }

    private ObjectMapperWrapper() {
    }

    /**
     * 将对象序列化为 JSON 字符串。
     *
     * @param value 待序列化对象
     * @param <T>   对象类型
     * @return JSON 字符串
     */
    public static <T> String writeValueAsString(T value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new JellyfishException("failed to serialize object: " + value, e);
        }
    }

    /**
     * 将 JSON 字符串反序列化为指定类型。
     *
     * @param json        JSON 字符串
     * @param targetClass 目标类型
     * @param <T>         目标类型
     * @return 反序列化结果
     */
    public static <T> T readValue(String json, Class<T> targetClass) {
        try {
            return MAPPER.readValue(json, targetClass);
        } catch (JsonProcessingException e) {
            throw new JellyfishException("failed to deserialize json to " + targetClass.getName(), e);
        }
    }

    /**
     * 将 JSON 字符串解析为 JsonNode 树。
     * <p>
     * 供配置读取链在绑定到具体类型之前做结构化处理（例如环境变量替换），
     * 避免在裸文本上做替换而产生非法 JSON。
     *
     * @param json JSON 字符串
     * @return JSON 树根节点
     */
    public static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new JellyfishException("failed to parse json", e);
        }
    }

    /**
     * 将 JsonNode 树转换为指定类型。
     *
     * @param node        JSON 树根节点
     * @param targetClass 目标类型
     * @param <T>         目标类型
     * @return 反序列化结果
     */
    public static <T> T treeToValue(JsonNode node, Class<T> targetClass) {
        try {
            return MAPPER.treeToValue(node, targetClass);
        } catch (JsonProcessingException e) {
            throw new JellyfishException("failed to convert json tree to " + targetClass.getName(), e);
        }
    }

    /**
     * 将 JSON 字符串反序列化为带泛型的类型。
     *
     * @param json          JSON 字符串
     * @param typeReference 泛型类型引用
     * @param <T>           目标类型
     * @return 反序列化结果
     */
    public static <T> T readValue(String json, TypeReference<T> typeReference) {
        try {
            return MAPPER.readValue(json, typeReference);
        } catch (JsonProcessingException e) {
            throw new JellyfishException("failed to deserialize json to " + typeReference.getType(), e);
        }
    }
}
