package zcd.jellyfish.plugin.todo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.event.notification.UiInvalidatedEvent;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PromptContributionRequest;
import zcd.jellyfish.api.extension.StatusLineContributionRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.plugin.PluginContextImpl;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TodoPlugin} 的单元测试：验证四个扩展点的注册，以及 {@code todo_write} 成功后的 UI 失效广播。
 * <p>
 * 用真实的 {@link PluginContextImpl} 与真实注册表（而不是 mock 上下文）：本类的职责就是「把四个处理器
 * 交给上下文」，用真实上下文能顺带验证它们确实落到了同一份注册表上、并且 owner 归本插件。
 * 只有插件声明是构造出来的——这样才能把 {@code todoDir} 指到临时目录，避免测试写进用户主目录。
 *
 * @author zcd
 */
@DisplayName("待办插件装配")
class TodoPluginTest {

    /** 每个用例一个独立目录。 */
    @TempDir
    Path directory;

    /** 共用注册表。 */
    private TypeRegistry registry;

    /** 同步扩展点策略。 */
    private ExtensionRegistry extensions;

    /** 事件通道。 */
    private EventChannel events;

    @BeforeEach
    void setUp() {
        registry = new TypeRegistry();
        extensions = new ExtensionRegistry(registry);
        events = new EventChannel(EventChannelOptions.defaults(), registry);
        events.start();
        Map<String, Object> configuration = new LinkedHashMap<String, Object>();
        configuration.put("todoDir", directory.toString());
        PluginContext context = new PluginContextImpl(
                PluginDeclaration.of("jellyfish-todo", configuration), extensions, events);
        new TodoPlugin().start(context);
    }

    @AfterEach
    void tearDown() {
        events.close();
    }

    @Test
    @DisplayName("四个面各注册一次：命令 / 工具 / 提示词注入 / 状态栏")
    void start_should_registerAllFourCapabilities() {
        assertEquals(1, extensions.bindings(CommandRequest.class, "todo").size());
        assertEquals(1, extensions.bindings(ToolCallRequest.class, TodoWriteTool.NAME).size());
        assertEquals(1, extensions.bindings(PromptContributionRequest.class, null).size());
        assertEquals(1, extensions.bindings(StatusLineContributionRequest.class, null).size());
    }

    @Test
    @DisplayName("todo_write 成功后广播 UI 失效：状态栏不必等回合结束才刷新")
    void writeTool_should_publishUiInvalidatedEvent() throws Exception {
        CountDownLatch invalidated = new CountDownLatch(1);
        events.subscribe("test-probe", UiInvalidatedEvent.class, event -> invalidated.countDown());

        writeTool().handle(request());

        assertTrue(invalidated.await(3L, TimeUnit.SECONDS), "未收到 UI 失效事件");
    }

    /**
     * 取注册进内核的 {@code todo_write} 处理器。
     *
     * @return 工具处理器
     */
    private ExtensionHandler<ToolCallRequest, ToolCallResult> writeTool() {
        return extensions.bindings(ToolCallRequest.class, TodoWriteTool.NAME).get(0).getHandler();
    }

    /**
     * 构造一次只含一条待办的工具调用。
     *
     * @return 工具调用请求
     */
    private static ToolCallRequest request() {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("content", "写文档");
        item.put("status", "pending");
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("todos", Collections.singletonList(item));
        return new ToolCallRequest(TodoWriteTool.NAME, arguments, "s-1");
    }
}
