package zcd.jellyfish.core.prompt;

import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.llm.LlmTool;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 工具目录：把注册表里的 {@link ToolDescriptor} 投影成厂商无关的 {@link LlmTool}。
 * <p>
 * <b>不维护第二份目录</b>：工具描述符随 handler 一起落 {@code TypeRegistry}，
 * 插件下架（{@code unregisterAll}）时描述符自动消失，这里每次现取即可，天然跟随热部署。
 * <p>
 * <b>不缓存</b>：工具清单是每轮构建请求时现取的，插件热部署后立刻可见；O(工具数) 的投影可以忽略。
 *
 * @author zcd
 */
@Singleton
public class ToolCatalog {

    /** 同步扩展点策略，工具描述符的唯一来源。 */
    private final ExtensionRegistry extensions;

    /**
     * 构造工具目录。
     *
     * @param extensions 同步扩展点策略，不可为 {@code null}
     */
    @Inject
    public ToolCatalog(ExtensionRegistry extensions) {
        this.extensions = Objects.requireNonNull(extensions, "extensions must not be null");
    }

    /**
     * 列出当前可用的工具定义。
     *
     * @return 不可修改的 {@link LlmTool} 列表，无工具时为空列表
     */
    public List<LlmTool> tools() {
        return tools(ToolFilter.none());
    }

    /**
     * 列出经过过滤的工具定义。
     * <p>
     * <b>过滤只作用于清单</b>：被滤掉的工具仍然可以在执行期被调用（只是模型看不到它，
     * 不会主动去调）；执行期的准入仍然由权限判定把关。这条分工是刻意的——
     * 清单是「建议」，权限是「约束」。
     *
     * @param filter 过滤器，不可为 {@code null}
     * @return 不可修改的 {@link LlmTool} 列表，无命中时为空列表
     */
    public List<LlmTool> tools(ToolFilter filter) {
        Objects.requireNonNull(filter, "filter must not be null");
        List<ToolDescriptor> descriptors =
                extensions.descriptors(ToolCallRequest.class, ToolDescriptor.class);
        List<LlmTool> tools = new ArrayList<LlmTool>(descriptors.size());
        for (ToolDescriptor descriptor : descriptors) {
            if (!filter.accepts(descriptor.getName())) {
                continue;
            }
            tools.add(new LlmTool(descriptor.getName(), descriptor.getDescription(),
                    descriptor.getParameters(), descriptor.getRequired()));
        }
        return Collections.unmodifiableList(tools);
    }
}
