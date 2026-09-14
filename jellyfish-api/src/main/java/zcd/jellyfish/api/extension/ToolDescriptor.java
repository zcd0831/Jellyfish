package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具描述符：插件注册工具处理器时与处理器一起落表的「工具名片」。
 * <p>
 * 放在 {@code api} 而不是 {@code infra} 是刻意的：插件作者必须能描述自己的工具
 * （名称、用途、参数 Schema），否则就没法注册工具。内核在 ReAct 侧把它转换为
 * {@code LlmTool} 交给模型，因此这里只保留与厂商无关的字段，不出现任何 LLM SDK 类型。
 * <p>
 * 与处理器一起存表的好处：工具清单不需要第二份目录，插件下架时描述符随注册一起消失。
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ToolDescriptor {

    /** 工具名，同时是工具调用的路由键。 */
    private final String name;

    /** 工具用途描述，模型据此判断何时调用。 */
    private final String description;

    /** 参数的 JSON Schema properties 部分，key 为参数名。 */
    private final Map<String, Object> parameters;

    /** 必填参数名列表，为空表示无必填参数。 */
    private final List<String> required;

    /**
     * 构造工具描述符。
     *
     * @param name        工具名，不可为空白
     * @param description 工具用途描述，可为 {@code null}
     * @param parameters  参数的 JSON Schema properties，可为 {@code null}
     * @param required    必填参数名列表，可为 {@code null}
     * @throws JellyfishException 工具名为空白时抛出
     */
    public ToolDescriptor(String name, String description, Map<String, Object> parameters, List<String> required) {
        if (name == null || name.trim().isEmpty()) {
            throw new JellyfishException("tool descriptor name must not be blank");
        }
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
     * 构造不带参数的工具描述符。
     *
     * @param name        工具名，不可为空白
     * @param description 工具用途描述，可为 {@code null}
     * @throws JellyfishException 工具名为空白时抛出
     */
    public ToolDescriptor(String name, String description) {
        this(name, description, null, null);
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
     * @return 工具用途描述，未提供时为 {@code null}
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
     * 获取必填参数名列表。
     *
     * @return 必填参数名，可能为空但不会为 {@code null}
     */
    public List<String> getRequired() {
        return required;
    }

    @Override
    public String toString() {
        return "ToolDescriptor{name=" + name + '}';
    }
}
