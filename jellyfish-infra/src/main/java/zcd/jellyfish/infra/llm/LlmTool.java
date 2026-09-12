package zcd.jellyfish.infra.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提供给 LLM 的工具（函数）定义。不可变，构造时对集合做防御性拷贝。
 *
 * @author zcd
 */
public final class LlmTool {

    /** 工具（函数）名，需符合厂商的命名约束。 */
    private final String name;

    /** 工具用途描述，模型据此判断何时调用。 */
    private final String description;

    /** 参数的 JSON Schema properties 部分，key 为参数名。 */
    private final Map<String, Object> parameters;

    /** 必填参数名列表，为空表示无必填参数。 */
    private final List<String> required;

    /**
     * 构造工具定义。
     *
     * @param name        工具名
     * @param description 工具用途描述
     * @param parameters  参数的 JSON Schema properties，可为 {@code null}
     * @param required    必填参数名列表，可为 {@code null}
     */
    public LlmTool(String name, String description, Map<String, Object> parameters, List<String> required) {
        this.name = name;
        this.description = description;
        this.parameters = parameters == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        this.required = required == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(required));
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
     * 获取工具用途描述。
     *
     * @return 工具用途描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * 获取 JSON Schema 的 properties 部分。
     *
     * @return 参数定义，可能为空但不会为 {@code null}
     */
    public Map<String, Object> getParameters() {
        return parameters;
    }

    /**
     * 获取必填参数名列表。
     *
     * @return 必填参数名，可能为空但不会为 {@code null}
     */
    public List<String> getRequired() {
        return required;
    }
}
