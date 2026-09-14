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
        List<ToolDescriptor> descriptors =
                extensions.descriptors(ToolCallRequest.class, ToolDescriptor.class);
        List<LlmTool> tools = new ArrayList<LlmTool>(descriptors.size());
        for (ToolDescriptor descriptor : descriptors) {
            tools.add(new LlmTool(descriptor.getName(), descriptor.getDescription(),
                    descriptor.getParameters(), descriptor.getRequired()));
        }
        return Collections.unmodifiableList(tools);
    }
}
