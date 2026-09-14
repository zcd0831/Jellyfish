package zcd.jellyfish.core.prompt;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.llm.LlmTool;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolCatalog} 的单元测试：验证描述符到 {@link LlmTool} 的投影、顺序与空表语义。
 * <p>
 * 用真实 {@code ExtensionRegistry} + 真实 {@code TypeRegistry}：工具目录的全部价值就在于
 * 「从注册表取描述符」，mock 掉它等于什么都没测。
 *
 * @author zcd
 */
class ToolCatalogTest {

    @Test
    void tools_should_return_empty_when_no_registration() {
        // Given
        ToolCatalog catalog = new ToolCatalog(newRegistry());

        // When / Then
        assertTrue(catalog.tools().isEmpty());
    }

    @Test
    void tools_should_map_descriptor_fields_in_registration_order() {
        // Given
        ExtensionRegistry extensions = newRegistry();
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        parameters.put("path", Collections.singletonMap("type", "string"));
        register(extensions, "read", new ToolDescriptor("read", "读文件", parameters,
                Collections.singletonList("path")));
        register(extensions, "write", new ToolDescriptor("write", "写文件"));

        // When
        List<LlmTool> tools = new ToolCatalog(extensions).tools();

        // Then
        assertEquals(Arrays.asList("read", "write"), Arrays.asList(
                tools.get(0).getName(), tools.get(1).getName()));
        assertEquals("读文件", tools.get(0).getDescription());
        assertEquals(Collections.singletonList("path"), tools.get(0).getRequired());
        assertTrue(tools.get(0).getParameters().containsKey("path"));
    }

    @Test
    void tools_should_skip_registration_without_descriptor() {
        // Given：没有描述符的注册不构成一个可下发给模型的工具
        ExtensionRegistry extensions = newRegistry();
        extensions.handle("p1", ToolCallRequest.class, "raw", null, noOp(), RegisterOptions.DEFAULT);

        // When / Then
        assertTrue(new ToolCatalog(extensions).tools().isEmpty());
    }

    @Test
    void tools_should_return_unmodifiable_list() {
        // Given
        ExtensionRegistry extensions = newRegistry();
        register(extensions, "read", new ToolDescriptor("read", "读文件"));
        List<LlmTool> tools = new ToolCatalog(extensions).tools();

        // When / Then
        assertThrows(UnsupportedOperationException.class,
                () -> tools.add(new LlmTool("x", "y", null, null)));
    }

    /**
     * 注册一个工具处理器。
     *
     * @param extensions 同步扩展点策略
     * @param name       工具名
     * @param descriptor 工具描述符
     */
    private static void register(ExtensionRegistry extensions, String name, ToolDescriptor descriptor) {
        extensions.handle("p1", ToolCallRequest.class, name, descriptor, noOp(), RegisterOptions.DEFAULT);
    }

    /**
     * 构造一个什么都不做的工具处理器。
     *
     * @return 处理器
     */
    private static ExtensionHandler<ToolCallRequest, ToolCallResult> noOp() {
        return request -> null;
    }

    /**
     * 构造空的同步扩展点策略。
     *
     * @return 同步扩展点策略
     */
    private static ExtensionRegistry newRegistry() {
        return new ExtensionRegistry(new TypeRegistry());
    }
}
