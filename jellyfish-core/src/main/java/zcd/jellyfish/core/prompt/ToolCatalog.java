package zcd.jellyfish.core.prompt;

import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.infra.extension.DescriptorBinding;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.llm.LlmTool;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 工具目录：把注册表里的 {@link ToolDescriptor} 投影成厂商无关的 {@link LlmTool}。
 * <p>
 * <b>不维护第二份目录</b>：工具描述符随 handler 一起落 {@code TypeRegistry}，
 * 插件下架（{@code unregisterAll}）时描述符自动消失，这里每次现取即可，天然跟随热部署。
 * <p>
 * <b>不缓存，但顺序稳定</b>：工具清单进的是缓存前缀里一个很靠前的位置（它在多数厂商的模板里
 * 排在 messages 之前），因此它的名字、描述、<b>以及顺序</b>一变，整段请求就作废。
 * 注册表给的顺序是 {@code order} 升序 + 注册顺序，而后者就等于插件加载顺序——同序的两项谁先谁后
 * 会随加载时机变化。这里再按<b>名称</b>排一次，把顺序从「加载顺序」变成「声明的内容本身决定的」。
 * <p>
 * 这是同一次改动里的重要细节：{@code MCP} 这类插件在启动超时后会异步追加一批工具，
 * 若工具的 <b>集合</b>在会话中途变化，无论怎么排序都救不了前缀（工具清单那一段直接不同）。
 * 因此那只保证顺序稳定，集合稳定靠插件侧的约定，见 {@code docs/constraints/react-compact.md}。
 *
 * @author zcd
 */
@Singleton
public class ToolCatalog {

    /**
     * 工具清单的排序键：{@code order} 升序，同序按<b>名称</b>。
     * <p>
     * 名称用作同序的第二关键字，而不是回到注册顺序：名称是内容的一部分，注册顺序不是。
     */
    private static final Comparator<DescriptorBinding<ToolDescriptor>> TOOL_ORDER =
            Comparator.comparingInt(DescriptorBinding<ToolDescriptor>::getOrder)
                    .thenComparing(binding -> nameOf(binding.getDescriptor()));

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
        List<DescriptorBinding<ToolDescriptor>> bindings =
                new ArrayList<DescriptorBinding<ToolDescriptor>>(
                        extensions.descriptorBindings(ToolCallRequest.class, ToolDescriptor.class));
        bindings.sort(TOOL_ORDER);
        List<LlmTool> tools = new ArrayList<LlmTool>(bindings.size());
        for (DescriptorBinding<ToolDescriptor> binding : bindings) {
            ToolDescriptor descriptor = binding.getDescriptor();
            if (descriptor == null || !filter.accepts(descriptor.getName())) {
                continue;
            }
            tools.add(new LlmTool(descriptor.getName(), descriptor.getDescription(),
                    descriptor.getParameters(), descriptor.getRequired()));
        }
        return Collections.unmodifiableList(tools);
    }

    /**
     * 取描述符里的工具名，供排序用。
     *
     * @param descriptor 描述符，可为 {@code null}
     * @return 工具名；描述符缺失时返回空串，使这类项排到同序的最前
     */
    private static String nameOf(ToolDescriptor descriptor) {
        return descriptor == null || descriptor.getName() == null ? "" : descriptor.getName();
    }
}
