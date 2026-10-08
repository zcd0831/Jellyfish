package zcd.jellyfish.infra.support;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import zcd.jellyfish.api.JellyfishException;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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
     * 将对象序列化为 JSON 字节。
     * <p>
     * <b>为什么直接出字节而不是先出字符串再取 {@code getBytes}</b>：HTTP 响应体本身就是字节流，
     * 多一步转码只多一次分配；而「统一走本类」的约定仍然成立——调用点拿到的字节一定来自同一个 MAPPER。
     *
     * @param value 待序列化对象
     * @return JSON 字节
     */
    public static byte[] writeValueAsBytes(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new JellyfishException("failed to serialize object: " + value, e);
        }
    }

    /**
     * 将 JSON 字节反序列化为指定类型。
     * <p>
     * 供 HTTP 请求体直接解析，避免先把字节拼成字符串再解析。
     *
     * @param json        JSON 字节
     * @param targetClass 目标类型
     * @param <T>         目标类型
     * @return 反序列化结果
     */
    public static <T> T readValue(byte[] json, Class<T> targetClass) {
        try {
            return MAPPER.readValue(json, targetClass);
        } catch (IOException e) {
            throw new JellyfishException("failed to deserialize json to " + targetClass.getName(), e);
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
     * 找出 JSON 里<b>绑不到目标类型</b>的字段名，用来发现「拼错了却无声生效」的配置。
     * <p>
     * <b>为什么要有它</b>：本类刻意开了 {@code FAIL_ON_UNKNOWN_PROPERTIES=false}（配置文件要向前兼容，
     * 多一个字段不该让进程起不来）。代价是拼错<b>完全没有提示</b>——用户写了 {@code "erros": {...}}
     * 却以为自己的配置生效了，而现场表现是「配置明明写了却没作用」。这个方法把那件事变成一条告警。
     * <p>
     * <b>只比字段名，不做类型转换</b>：真去做一次严格反序列化会把两类无关的东西也报出来——
     * 环境变量占位符（{@code "${PORT}"} 放不进 int 字段）与「值本身写错」。那些各有各的报错路径，
     * 不该混进「字段名不认识」这条告警里。
     * <p>
     * <b>自由格式的地方一概不看</b>：{@code Map<String,Object>}、{@code Object}、{@code JsonNode}
     * 这些位置上的键名由使用方决定（{@code vendorBody}、逐插件配置段都是这样），
     * 标了 {@code @JsonAnySetter} 的类型同理。宁可漏报，也不报一堆假警。
     * <p>
     * JSON 解析失败时返回空清单：那是 {@link #readTree} 与绑定路径上的事，不必在这里重复报一遍。
     *
     * @param json        JSON 文本，可为 {@code null} 或空白
     * @param targetClass 目标类型，可为 {@code null}
     * @return 未知字段的路径清单（如 {@code providers.openai.vondorBody}），没有时为空清单
     */
    public static List<String> unknownFields(String json, Class<?> targetClass) {
        if (json == null || json.trim().isEmpty() || targetClass == null) {
            return Collections.emptyList();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            return Collections.emptyList();
        }
        List<String> unknown = new ArrayList<String>();
        collectUnknownFields(root, MAPPER.constructType(targetClass), "", unknown);
        return unknown;
    }

    /**
     * 按类型递归比对 JSON 对象的字段名。
     *
     * @param node  当前节点
     * @param type  当前节点的目标类型
     * @param path  已经走过的路径（用于拼出可定位的字段名）
     * @param out   未知字段收集器
     */
    private static void collectUnknownFields(JsonNode node, JavaType type, String path, List<String> out) {
        if (node == null || type == null) {
            return;
        }
        if (type.isCollectionLikeType() || type.isArrayType()) {
            // 数组/集合要先判类型再看节点：它们的节点是 array，不是 object
            if (node.isArray()) {
                int index = 0;
                for (JsonNode element : node) {
                    collectUnknownFields(element, type.getContentType(), path + "[" + index + "]", out);
                    index++;
                }
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        if (type.isMapLikeType()) {
            // Map 的键名是数据，不是字段名：逐个看值
            collectEntries(node, type.getContentType(), path, out);
            return;
        }
        if (type.isJavaLangObject() || type.isTypeOrSubTypeOf(JsonNode.class)) {
            // 自由格式：键名由使用方决定
            return;
        }
        Class<?> raw = type.getRawClass();
        if (raw == null || raw.getName().startsWith("java.") || hasAnySetter(raw)) {
            // 标量包装类型不必看；@JsonAnySetter 意味着「任意键都合法」
            return;
        }
        BeanDescription description = MAPPER.getDeserializationConfig().introspect(type);
        Map<String, BeanPropertyDefinition> known = new HashMap<String, BeanPropertyDefinition>();
        for (BeanPropertyDefinition property : description.findProperties()) {
            if (property.getName() != null) {
                known.put(property.getName(), property);
            }
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            String field = entry.getKey();
            String childPath = path.isEmpty() ? field : path + "." + field;
            BeanPropertyDefinition property = known.get(field);
            if (property == null) {
                out.add(childPath);
                continue;
            }
            collectUnknownFields(entry.getValue(), property.getPrimaryType(), childPath, out);
        }
    }

    /**
     * 按 Map 的取值类型逐个检查值。
     *
     * @param node        当前对象节点
     * @param valueType   取值的类型，可为 {@code null}
     * @param path        已经走过的路径
     * @param out         未知字段收集器
     */
    private static void collectEntries(JsonNode node, JavaType valueType, String path, List<String> out) {
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            // 键名是数据（provider 名、插件 id……），因此不进路径的「字段」位置，但仍拼出来便于定位
            collectUnknownFields(entry.getValue(), valueType, path + "." + entry.getKey(), out);
        }
    }

    /**
     * 判断类型上有没有 {@code @JsonAnySetter}。
     * <p>
     * 用反射而不是 Jackson 的内省 API：那个方法名在各版本间改过（{@code findAnySetter} /
     * {@code findAnySetterAccessor}），而这里只关心「有没有」，反射更稳。
     *
     * @param raw 原始类型
     * @return 有则返回 {@code true}
     */
    private static boolean hasAnySetter(Class<?> raw) {
        for (Method method : raw.getDeclaredMethods()) {
            if (method.isAnnotationPresent(JsonAnySetter.class)) {
                return true;
            }
        }
        for (Field field : raw.getDeclaredFields()) {
            if (field.isAnnotationPresent(JsonAnySetter.class)) {
                return true;
            }
        }
        return false;
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
