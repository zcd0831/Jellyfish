package zcd.jellyfish.plugin.tools;

import zcd.jellyfish.api.JellyfishException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具参数 Schema 的小构造器。
 * <p>
 * 内核只要求 {@code ToolDescriptor.parameters} 是 JSON Schema 的 {@code properties} 部分
 * （{@code type: object} 与 {@code required} 由内核在转成厂商请求时自己包上），
 * 因此这里只负责「一个参数名 → 一段带类型的描述」。
 * <p>
 * 刻意不引入任何 JSON 库：工具插件只依赖 {@code jellyfish-api}，
 * 参数 Schema 用普通 Map 就能表达，多一个依赖就多一份需要 shade 进插件包的东西。
 *
 * @author zcd
 */
final class ToolSchema {

    /** JSON Schema 的类型键。 */
    private static final String TYPE = "type";

    /**
     * 工具类，禁止实例化。
     */
    private ToolSchema() {
    }

    /**
     * 构造字符串参数描述。
     *
     * @param description 参数用途，不可为 {@code null}
     * @return 参数 Schema
     */
    static Map<String, Object> string(String description) {
        return typed("string", description);
    }

    /**
     * 构造整数参数描述。
     *
     * @param description 参数用途，不可为 {@code null}
     * @return 参数 Schema
     */
    static Map<String, Object> integer(String description) {
        return typed("integer", description);
    }

    /**
     * 构造布尔参数描述。
     *
     * @param description 参数用途，不可为 {@code null}
     * @return 参数 Schema
     */
    static Map<String, Object> bool(String description) {
        return typed("boolean", description);
    }

    /**
     * 按「参数名 / 参数 Schema」交替的行文组装 properties。
     * <p>
     * 用交替参数而不是 {@code Map}：调用点读起来就是声明顺序，
     * 也不会出现「往一个 Map 里塞了奇数个键值」的静默错误。
     *
     * @param namesAndSchemas 参数名与 Schema 交替出现，个数必须为偶数
     * @return 有序的 properties 映射
     * @throws JellyfishException 参数个数为奇数或参数名重复时抛出
     */
    static Map<String, Object> properties(Object... namesAndSchemas) {
        if (namesAndSchemas.length % 2 != 0) {
            throw new JellyfishException("工具参数 Schema 必须成对给出：参数名 + 描述");
        }
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        for (int i = 0; i < namesAndSchemas.length; i += 2) {
            Object name = namesAndSchemas[i];
            if (!(name instanceof String)) {
                throw new JellyfishException("工具参数名必须是字符串: " + name);
            }
            properties.put((String) name, namesAndSchemas[i + 1]);
        }
        return properties;
    }

    /**
     * 组装单个参数 Schema。
     *
     * @param type        JSON Schema 类型
     * @param description 参数用途
     * @return 参数 Schema
     */
    private static Map<String, Object> typed(String type, String description) {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put(TYPE, type);
        schema.put("description", description);
        return schema;
    }
}
