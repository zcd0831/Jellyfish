package zcd.jellyfish.api.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 传输层看到的一次工具（函数）定义。
 * <p>
 * <b>参数 schema 拆成两半</b>（{@code properties} + {@code required}）：这就是协议里
 * {@code parameters} 的形状，而合成整个 JSON Schema 对象是插件的活——内核只保证「这些工具、这个顺序、
 * 这份描述与参数定义」被原样送到。做成三个字段而不是一个 {@code Map}，是为了让插件的映射无需理解
 * 内核的内部表示。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class LlmTransportTool {

    /** 工具名。 */
    private final String name;

    /** 用途描述，模型据此判断何时调用。 */
    private final String description;

    /** 参数的 JSON Schema properties 部分，key 为参数名。 */
    private final Map<String, Object> parameters;

    /** 必填参数名列表。 */
    private final List<String> required;

    /**
     * 构造工具定义。
     *
     * @param name        工具名，不可为空白
     * @param description 用途描述，可为 {@code null}
     * @param parameters  参数的 JSON Schema properties，可为 {@code null}
     * @param required    必填参数名，可为 {@code null}
     */
    public LlmTransportTool(String name, String description, Map<String, Object> parameters,
                            List<String> required) {
        this.name = name;
        this.description = description;
        this.parameters = parameters == null || parameters.isEmpty()
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(parameters));
        this.required = required == null || required.isEmpty()
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(required));
    }

    /**
     * 获取工具名。
     *
     * @return 工具名
     */
    public String getName() {
        return name;
    }

    /**
     * 获取用途描述。
     *
     * @return 用途描述，可能为 {@code null}
     */
    public String getDescription() {
        return description;
    }

    /**
     * 获取参数的 JSON Schema properties。
     *
     * @return 参数定义，可能为空但不会为 {@code null}
     */
    public Map<String, Object> getParameters() {
        return parameters;
    }

    /**
     * 获取必填参数名。
     *
     * @return 必填参数名，可能为空但不会为 {@code null}
     */
    public List<String> getRequired() {
        return required;
    }

    @Override
    public String toString() {
        return "LlmTransportTool{name=" + name + ", parameters=" + parameters.size() + '}';
    }
}
