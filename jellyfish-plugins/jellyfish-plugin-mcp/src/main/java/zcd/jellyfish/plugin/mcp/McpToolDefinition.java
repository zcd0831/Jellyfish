package zcd.jellyfish.plugin.mcp;

import zcd.jellyfish.api.extension.ToolDescriptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个 MCP 工具在内核里的完整定义：原始名、展开名、名片与只读标记。
 * <p>
 * <b>原始名与展开名都要留着</b>：内核看到的是展开名（工具名要去重），而 {@code tools/call} 时必须
 * 把原始名发回给 server。少了任何一个都会落进「拿显示名去调用」这类越查越远的错误。
 * <p>
 * <b>只读标记在这里就算好</b>：它由「server 的 hint」与「用户配置的只读清单」共同决定，
 * 而权限拦截那一侧只看结果。把判定散到两处，迟早会出现「清单里算只读、拦截时算可写」。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class McpToolDefinition {

    /** 所属 server 标识。 */
    private final String serverId;

    /** server 给的原始工具名，调用时要用它。 */
    private final String originalName;

    /** 内核里使用的工具名。 */
    private final String qualifiedName;

    /** 工具描述。 */
    private final String description;

    /** 参数 Schema 的 properties 部分。 */
    private final Map<String, Object> parameters;

    /** 必填参数名。 */
    private final List<String> required;

    /** 是否为只读工具。 */
    private final boolean readOnly;

    /**
     * 构造定义。
     *
     * @param serverId      所属 server 标识
     * @param originalName  server 给的原始工具名
     * @param qualifiedName 内核里使用的工具名
     * @param description   工具描述
     * @param parameters    参数 Schema properties
     * @param required      必填参数名
     * @param readOnly      是否为只读工具
     */
    McpToolDefinition(String serverId, String originalName, String qualifiedName, String description,
                      Map<String, Object> parameters, List<String> required, boolean readOnly) {
        this.serverId = serverId;
        this.originalName = originalName;
        this.qualifiedName = qualifiedName;
        this.description = description;
        this.parameters = parameters == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(parameters));
        this.required = required == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(required));
        this.readOnly = readOnly;
    }

    /**
     * 获取所属 server 标识。
     *
     * @return 标识
     */
    String serverId() {
        return serverId;
    }

    /**
     * 获取原始工具名。
     *
     * @return 原始名
     */
    String originalName() {
        return originalName;
    }

    /**
     * 获取内核里使用的工具名。
     *
     * @return 展开名
     */
    String qualifiedName() {
        return qualifiedName;
    }

    /**
     * 获取工具描述。
     *
     * @return 描述
     */
    String description() {
        return description;
    }

    /**
     * 获取参数 Schema properties。
     *
     * @return 不可变映射，保证非 {@code null}
     */
    Map<String, Object> parameters() {
        return parameters;
    }

    /**
     * 获取必填参数名。
     *
     * @return 不可变列表，保证非 {@code null}
     */
    List<String> required() {
        return required;
    }

    /**
     * 判断是否为只读工具。
     *
     * @return 只读返回 {@code true}
     */
    boolean readOnly() {
        return readOnly;
    }

    /**
     * 组装内核工具名片。
     * <p>
     * 描述里带上 server 标识：模型看到的工具名已经带了前缀，但描述里的这一句能让它（以及人）
     * 在「这个工具为什么叫这个名字」上少一次推断。
     *
     * @return 工具名片
     */
    ToolDescriptor descriptor() {
        String label = "[MCP:" + serverId + "] " + (description == null || description.trim().isEmpty()
                ? originalName : description.trim());
        return new ToolDescriptor(qualifiedName, label, parameters, required, readOnly);
    }

    @Override
    public String toString() {
        return "McpToolDefinition{" + serverId + ":" + originalName + " -> " + qualifiedName + '}';
    }
}
