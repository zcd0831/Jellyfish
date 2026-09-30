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

    @Test
    void tools_should_drop_entries_rejected_by_filter() {
        // Given
        ExtensionRegistry extensions = newRegistry();
        register(extensions, "read_file", new ToolDescriptor("read_file", "读文件"));
        register(extensions, "write_file", new ToolDescriptor("write_file", "写文件"));

        // When
        List<LlmTool> tools = new ToolCatalog(extensions).tools(ToolFilter.of("read_file"::equals));

        // Then
        assertEquals(1, tools.size());
        assertEquals("read_file", tools.get(0).getName());
    }

    @Test
    void tools_should_return_all_when_filter_is_none() {
        // Given
        ExtensionRegistry extensions = newRegistry();
        register(extensions, "read_file", new ToolDescriptor("read_file", "读文件"));
        register(extensions, "write_file", new ToolDescriptor("write_file", "写文件"));

        // When
        List<LlmTool> tools = new ToolCatalog(extensions).tools(ToolFilter.none());

        // Then：主会话走的就是这条路径，必须与引入过滤器之前完全一致
        assertEquals(2, tools.size());
    }

    @Test
    void tools_should_reject_null_filter() {
        // When / Then：传 null 是编程错误，静默当成全放行会把过滤变成可选项
        assertThrows(NullPointerException.class, () -> new ToolCatalog(newRegistry()).tools(null));
    }

    @Test
    void tools_should_orderByOrderThenName_independentOfRegistrationOrder() {
        // Given：两份注册表，注册顺序正好相反（模拟插件加载顺序不同）
        ExtensionRegistry first = newRegistry();
        register(first, "b_write", new ToolDescriptor("b_write", "写文件"), RegisterOptions.order(5));
        register(first, "a_read", new ToolDescriptor("a_read", "读文件"), RegisterOptions.order(5));
        ExtensionRegistry second = newRegistry();
        register(second, "a_read", new ToolDescriptor("a_read", "读文件"), RegisterOptions.order(5));
        register(second, "b_write", new ToolDescriptor("b_write", "写文件"), RegisterOptions.order(5));

        // When
        List<String> namesOfFirst = namesOf(new ToolCatalog(first).tools());
        List<String> namesOfSecond = namesOf(new ToolCatalog(second).tools());

        // Then：同序时按名称，因此与「谁先加载」无关。工具清单在多数厂商的模板里排在 messages
        // 之前，顺序一变整段请求连同全部历史就作废——这正是 R5
        assertEquals(Arrays.asList("a_read", "b_write"), namesOfFirst);
        assertEquals(namesOfFirst, namesOfSecond);
    }

    @Test
    void tools_should_let_orderWin_overName() {
        // Given：若只看名称会得到 a_read 在前，但 order 更小的 b_write 应当排在它之前——
        // order 是扩展系统声明的排序机制，不能被静默忽略
        ExtensionRegistry extensions = newRegistry();
        register(extensions, "a_read", new ToolDescriptor("a_read", "读文件"), RegisterOptions.order(9));
        register(extensions, "b_write", new ToolDescriptor("b_write", "写文件"), RegisterOptions.order(1));

        // When / Then
        assertEquals(Arrays.asList("b_write", "a_read"),
                namesOf(new ToolCatalog(extensions).tools()));
    }

    @Test
    void tools_should_freezeSnapshot_perSession_when_registryChanges() {
        // Given：两个会话各取过一次清单
        ExtensionRegistry extensions = newRegistry();
        register(extensions, "a_read", new ToolDescriptor("a_read", "读文件"));
        ToolCatalog catalog = new ToolCatalog(extensions);
        assertEquals(Collections.singletonList("a_read"),
                namesOf(catalog.tools("s-1", ToolFilter.none())));
        assertEquals(Collections.singletonList("a_read"),
                namesOf(catalog.tools("s-2", ToolFilter.none())));

        // When：MCP 那类插件在会话中途又注册了一个工具
        register(extensions, "b_write", new ToolDescriptor("b_write", "写文件"));

        // Then：旧会话的清单不动——工具清单在厂商模板里排在 messages 之前，它一变整段请求作废
        assertEquals(Collections.singletonList("a_read"),
                namesOf(catalog.tools("s-1", ToolFilter.none())));
        assertEquals(Collections.singletonList("a_read"),
                namesOf(catalog.tools("s-2", ToolFilter.none())));
        // 而变化只对新会话生效，活性因此没有丢
        assertEquals(Arrays.asList("a_read", "b_write"),
                namesOf(catalog.tools("s-3", ToolFilter.none())));
        // 实时入口照旧看得到变化：它是给诊断用的，不是请求路径
        assertEquals(Arrays.asList("a_read", "b_write"), namesOf(catalog.tools(ToolFilter.none())));
    }

    @Test
    void tools_should_shareOneSnapshot_betweenDifferentFilters() {
        // Given：同一会话里主回合与子代理回合的过滤器不同，底稿必须是一份
        ExtensionRegistry extensions = newRegistry();
        register(extensions, "a_read", new ToolDescriptor("a_read", "读文件"));
        register(extensions, "b_write", new ToolDescriptor("b_write", "写文件"));
        ToolCatalog catalog = new ToolCatalog(extensions);
        catalog.tools("s-1", ToolFilter.none());

        // When：注册表变了，但子代理回合仍属于同一个会话
        register(extensions, "c_shell", new ToolDescriptor("c_shell", "跑命令"));

        // Then：过滤只作用于清单，底稿还是那一份——否则「这个会话的工具集到底变没变」又说不清了
        assertEquals(Collections.singletonList("b_write"),
                namesOf(catalog.tools("s-1", ToolFilter.of("b_write"::equals))));
    }

    /**
     * 取出工具名列表。
     *
     * @param tools 工具清单
     * @return 名称列表，保持清单顺序
     */
    private static List<String> namesOf(List<LlmTool> tools) {
        List<String> names = new java.util.ArrayList<String>(tools.size());
        for (LlmTool tool : tools) {
            names.add(tool.getName());
        }
        return names;
    }

    /**
     * 注册一个工具处理器。
     *
     * @param extensions 同步扩展点策略
     * @param name       工具名
     * @param descriptor 工具描述符
     */
    private static void register(ExtensionRegistry extensions, String name, ToolDescriptor descriptor) {
        register(extensions, name, descriptor, RegisterOptions.DEFAULT);
    }

    /**
     * 注册一个带调用顺序的工具处理器。
     *
     * @param extensions 同步扩展点策略
     * @param name       工具名
     * @param descriptor 工具描述符
     * @param options    注册选项（含 {@code order}）
     */
    private static void register(ExtensionRegistry extensions, String name, ToolDescriptor descriptor,
                                RegisterOptions options) {
        extensions.handle("p1", ToolCallRequest.class, name, descriptor, noOp(), options);
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
