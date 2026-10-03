package zcd.jellyfish.core.prompt;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ToolActivation;
import zcd.jellyfish.api.extension.ToolActivationRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.llm.LlmTool;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.session.Session;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
                namesOf(catalog.tools(session("s-1"), ToolFilter.none())));
        assertEquals(Collections.singletonList("a_read"),
                namesOf(catalog.tools(session("s-2"), ToolFilter.none())));

        // When：MCP 那类插件在会话中途又注册了一个工具
        register(extensions, "b_write", new ToolDescriptor("b_write", "写文件"));

        // Then：旧会话的清单不动——工具清单在厂商模板里排在 messages 之前，它一变整段请求作废
        assertEquals(Collections.singletonList("a_read"),
                namesOf(catalog.tools(session("s-1"), ToolFilter.none())));
        assertEquals(Collections.singletonList("a_read"),
                namesOf(catalog.tools(session("s-2"), ToolFilter.none())));
        // 而变化只对新会话生效，活性因此没有丢
        assertEquals(Arrays.asList("a_read", "b_write"),
                namesOf(catalog.tools(session("s-3"), ToolFilter.none())));
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
        catalog.tools(session("s-1"), ToolFilter.none());

        // When：注册表变了，但子代理回合仍属于同一个会话
        register(extensions, "c_shell", new ToolDescriptor("c_shell", "跑命令"));

        // Then：过滤只作用于清单，底稿还是那一份——否则「这个会话的工具集到底变没变」又说不清了
        assertEquals(Collections.singletonList("b_write"),
                namesOf(catalog.tools(session("s-1"), ToolFilter.of("b_write"::equals))));
    }

    @Test
    void tools_should_drop_hidden_tools_from_frozen_snapshot() {
        // Given：插件判定 mcp_search 不该进清单
        ExtensionRegistry extensions = newRegistry();
        register(extensions, "a_read", new ToolDescriptor("a_read", "读文件"));
        register(extensions, "mcp_search", new ToolDescriptor("mcp_search", "搜索"));
        extensions.contribute("plugin-a", ToolActivationRequest.class, null,
                request -> "mcp_search".equals(request.getToolName())
                        ? ToolActivation.hidden("本会话未连接该服务") : ToolActivation.abstain(),
                RegisterOptions.DEFAULT);
        ToolCatalog catalog = new ToolCatalog(extensions);

        // When / Then：清单里看不到它，但注册表里还在——「隐藏 ≠ 禁用」
        assertEquals(Collections.singletonList("a_read"),
                namesOf(catalog.tools(session("s-1"), ToolFilter.none())));
        assertEquals(Arrays.asList("a_read", "mcp_search"),
                namesOf(catalog.tools(ToolFilter.none())));
        assertEquals(2, extensions.descriptorBindings(ToolCallRequest.class, ToolDescriptor.class).size());
    }

    @Test
    void tools_should_pick_first_decided_handler_by_order() {
        // Given：一个要它可见、一个要它隐藏，order 小的那个说话算数
        ExtensionRegistry extensions = newRegistry();
        register(extensions, "mcp_search", new ToolDescriptor("mcp_search", "搜索"));
        extensions.contribute("plugin-abstaining", ToolActivationRequest.class, null,
                request -> ToolActivation.abstain(), RegisterOptions.order(1));
        extensions.contribute("plugin-visible", ToolActivationRequest.class, null,
                request -> ToolActivation.visible(), RegisterOptions.order(2));
        extensions.contribute("plugin-hiding", ToolActivationRequest.class, null,
                request -> ToolActivation.hidden("藏起来"), RegisterOptions.order(3));

        // Then：第一个表了态的胜出，后面的不再被问
        assertEquals(Collections.singletonList("mcp_search"),
                namesOf(new ToolCatalog(extensions).tools(session("s-1"), ToolFilter.none())));
    }

    @Test
    void tools_should_hide_when_hiding_handler_comes_first() {
        ExtensionRegistry extensions = newRegistry();
        register(extensions, "mcp_search", new ToolDescriptor("mcp_search", "搜索"));
        extensions.contribute("plugin-hiding", ToolActivationRequest.class, null,
                request -> ToolActivation.hidden("藏起来"), RegisterOptions.order(1));
        extensions.contribute("plugin-visible", ToolActivationRequest.class, null,
                request -> ToolActivation.visible(), RegisterOptions.order(2));

        assertTrue(namesOf(new ToolCatalog(extensions).tools(session("s-1"), ToolFilter.none())).isEmpty());
    }

    @Test
    void tools_should_keep_tool_visible_when_activation_handler_fails() {
        // 失败时保留工具比隐藏工具安全：隐藏会让模型「不知道有这个能力」而走进死路
        ExtensionRegistry extensions = newRegistry();
        register(extensions, "a_read", new ToolDescriptor("a_read", "读文件"));
        extensions.contribute("plugin-a", ToolActivationRequest.class, null, request -> {
            throw new IllegalStateException("判定服务挂了");
        }, RegisterOptions.DEFAULT);

        assertEquals(Collections.singletonList("a_read"),
                namesOf(new ToolCatalog(extensions).tools(session("s-1"), ToolFilter.none())));
    }

    @Test
    void tools_should_evaluate_activation_once_per_session() {
        // 只在冻结点求值：每轮问一次就等于把「清单逐轮可变」放回来了
        ExtensionRegistry extensions = newRegistry();
        register(extensions, "a_read", new ToolDescriptor("a_read", "读文件"));
        AtomicInteger calls = new AtomicInteger();
        extensions.contribute("plugin-a", ToolActivationRequest.class, null, request -> {
            calls.incrementAndGet();
            return ToolActivation.visible();
        }, RegisterOptions.DEFAULT);
        ToolCatalog catalog = new ToolCatalog(extensions);

        catalog.tools(session("s-1"), ToolFilter.none());
        catalog.tools(session("s-1"), ToolFilter.none());
        catalog.tools(session("s-1"), ToolFilter.of("a_read"::equals));
        int afterFirstSession = calls.get();

        // Then：过滤器不同、问几次都只有一次；新会话才再问一次
        assertEquals(1, afterFirstSession);
        catalog.tools(session("s-2"), ToolFilter.none());
        assertEquals(2, calls.get());
    }

    @Test
    void tools_should_pass_session_facts_to_activation_handler() {
        ExtensionRegistry extensions = newRegistry();
        register(extensions, "a_read", new ToolDescriptor("a_read", "读文件"));
        Session session = session("s-1");
        when(session.getAgentId()).thenReturn("subagent");
        List<String> seen = new java.util.ArrayList<String>();
        extensions.contribute("plugin-a", ToolActivationRequest.class, null, request -> {
            seen.add(request.getAgentId() + "/" + request.getSessionId());
            return ToolActivation.abstain();
        }, RegisterOptions.DEFAULT);

        new ToolCatalog(extensions).tools(session, ToolFilter.none());

        assertEquals(Collections.singletonList("subagent/s-1"), seen);
    }

    @Test
    void tools_should_stack_activation_and_filter() {
        // 两个闸门都放行才进清单：激活管「这个工具该不该出现」，过滤器管「这个 agent 能不能用」。
        // 它们叠加而不是合并——合成一个字段会让「这个工具为什么不在清单里」变成一个需要判别的问题
        ExtensionRegistry extensions = newRegistry();
        register(extensions, "a_read", new ToolDescriptor("a_read", "读文件"));
        register(extensions, "b_write", new ToolDescriptor("b_write", "写文件"));
        register(extensions, "c_shell", new ToolDescriptor("c_shell", "跑命令"));
        extensions.contribute("plugin-a", ToolActivationRequest.class, null,
                request -> "b_write".equals(request.getToolName())
                        ? ToolActivation.hidden("只读模式") : ToolActivation.abstain(),
                RegisterOptions.DEFAULT);

        List<String> names = namesOf(new ToolCatalog(extensions).tools(
                session("s-1"), ToolFilter.of(name -> !"c_shell".equals(name))));

        assertEquals(Collections.singletonList("a_read"), names);
    }

    @Test
    void tools_should_hide_tool_for_every_session_including_subagent() {
        // 子代理是同一个工具目录的另一个会话，因此它的清单冻结走的是同一次求值；
        // 「主代理看不到、子代理却看得到」会产生无法解释的不对称
        ExtensionRegistry extensions = newRegistry();
        register(extensions, "a_read", new ToolDescriptor("a_read", "读文件"));
        register(extensions, "mcp_search", new ToolDescriptor("mcp_search", "搜索"));
        extensions.contribute("plugin-a", ToolActivationRequest.class, null,
                request -> "mcp_search".equals(request.getToolName())
                        ? ToolActivation.hidden("本会话未连接该服务") : ToolActivation.abstain(),
                RegisterOptions.DEFAULT);
        ToolCatalog catalog = new ToolCatalog(extensions);
        Session subagent = session("s-sub");
        when(subagent.getAgentId()).thenReturn("explorer");

        assertEquals(Collections.singletonList("a_read"),
                namesOf(catalog.tools(session("s-main"), ToolFilter.none())));
        assertEquals(Collections.singletonList("a_read"),
                namesOf(catalog.tools(subagent, ToolFilter.none())));
    }

    @Test
    void rebuild_should_drop_frozen_snapshot_and_refreeze_nextTime() {
        // Given：会话已冻结一份清单
        ExtensionRegistry extensions = newRegistry();
        register(extensions, "a_read", new ToolDescriptor("a_read", "读文件"));
        ToolCatalog catalog = new ToolCatalog(extensions);
        catalog.tools(session("s-1"), ToolFilter.none());
        catalog.tools(session("s-2"), ToolFilter.none());
        register(extensions, "b_write", new ToolDescriptor("b_write", "写文件"));
        assertEquals(Collections.singletonList("a_read"),
                namesOf(catalog.tools(session("s-1"), ToolFilter.none())));

        // When
        assertTrue(catalog.rebuild("s-1"));

        // Then：只有被重建的那个会话跟上注册表变化，另一个照旧
        assertEquals(Arrays.asList("a_read", "b_write"),
                namesOf(catalog.tools(session("s-1"), ToolFilter.none())));
        assertEquals(Collections.singletonList("a_read"),
                namesOf(catalog.tools(session("s-2"), ToolFilter.none())));
    }

    @Test
    void rebuild_should_report_false_when_nothing_frozen() {
        ToolCatalog catalog = new ToolCatalog(newRegistry());

        assertFalse(catalog.rebuild("s-ghost"));
        assertFalse(catalog.rebuild(null));
    }

    /**
     * 造一个只带标识的会话。
     *
     * @param sessionId 会话标识
     * @return 会话
     */
    private static Session session(String sessionId) {
        Session session = mock(Session.class);
        when(session.getSessionId()).thenReturn(sessionId);
        return session;
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
