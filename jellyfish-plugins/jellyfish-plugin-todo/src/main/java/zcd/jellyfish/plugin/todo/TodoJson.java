package zcd.jellyfish.plugin.todo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import zcd.jellyfish.api.JellyfishException;

import java.io.IOException;
import java.util.List;

/**
 * 待办文件的 JSON 读写。
 * <p>
 * <b>为什么要自己造一个 ObjectMapper</b>：内核的 {@code ObjectMapperWrapper} 在 {@code jellyfish-infra}，
 * 插件只能看到 {@code jellyfish-api}，因此本插件自带 Jackson（由 shade 打进插件包）。
 * <p>
 * <b>未知字段不报错</b>：将来给待办加字段时，旧文件仍要能读出来——读不出来的代价是整份待办丢失，
 * 而少一个字段最多是某个新属性回到默认值。
 * <p>
 * 无状态（{@code ObjectMapper} 本身线程安全），可安全复用。
 *
 * @author zcd
 */
final class TodoJson {

    /** 共享的映射器，配置一次后只读使用。 */
    private static final ObjectMapper MAPPER = createMapper();

    /** 待办列表的泛型引用，用于反序列化。 */
    private static final TypeReference<List<TodoItem>> ITEM_LIST = new TypeReference<List<TodoItem>>() {
    };

    /**
     * 工具类，禁止实例化。
     */
    private TodoJson() {
    }

    /**
     * 把待办列表序列化成便于阅读与 diff 的 JSON。
     *
     * @param items 待办列表，不可为 {@code null}
     * @return JSON 文本
     * @throws JellyfishException 序列化失败时抛出
     */
    static String write(List<TodoItem> items) {
        try {
            return MAPPER.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            throw new JellyfishException("待办序列化失败", e);
        }
    }

    /**
     * 把 JSON 还原成待办列表。
     *
     * @param json   JSON 文本
     * @param source 来源描述（文件路径），仅用于异常信息
     * @return 待办列表，保证非 {@code null}
     * @throws JellyfishException JSON 非法或缺少必需字段时抛出
     */
    static List<TodoItem> read(String json, String source) {
        try {
            List<TodoItem> items = MAPPER.readValue(json, ITEM_LIST);
            if (items == null) {
                throw new JellyfishException("待办文件内容为空: " + source);
            }
            return items;
        } catch (JsonProcessingException e) {
            throw new JellyfishException("待办文件解析失败: " + source + " (" + e.getOriginalMessage() + ')', e);
        } catch (IOException e) {
            throw new JellyfishException("待办文件读取失败: " + source + " (" + e.getMessage() + ')', e);
        }
    }

    /**
     * 构造映射器。
     *
     * @return 已配置的映射器
     */
    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // 缩进输出：文件是要被人读、被 git diff 的
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        return mapper;
    }
}
