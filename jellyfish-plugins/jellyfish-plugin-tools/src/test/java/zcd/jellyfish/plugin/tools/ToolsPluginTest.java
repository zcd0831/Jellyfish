package zcd.jellyfish.plugin.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.plugin.PluginContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ToolsPlugin} 与 {@link PluginTool} 注册语义的单元测试。
 * <p>
 * 重点锁住「名片里的名字就是路由键」：注册时名字写两遍是最容易出错的地方，
 * 一处笔误就会让工具在模型侧可见、实际却调不到。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolsPlugin 工具注册")
class ToolsPluginTest {

    /** 插件上下文，只验证注册调用。 */
    @Mock
    private PluginContext context;

    @Test
    @DisplayName("启动时应注册全部五个工具，且都挂在 ToolCallRequest 上")
    void start_should_registerEveryTool() {
        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ToolDescriptor> descriptors = ArgumentCaptor.forClass(ToolDescriptor.class);

        new ToolsPlugin().start(context);

        verify(context, times(5)).handle(eq(ToolCallRequest.class), names.capture(), descriptors.capture(),
                ArgumentMatchers.<ExtensionHandler<ToolCallRequest, ToolCallResult>>any());
        List<String> registered = new ArrayList<String>(names.getAllValues());
        assertTrue(registered.containsAll(Arrays.asList(
                "read_file", "write_file", "edit_file", "list_dir", "grep_files")), registered.toString());
    }

    @Test
    @DisplayName("注册用的路由键必须与名片里的名字一致")
    void start_should_useDescriptorNameAsRouteKey() {
        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ToolDescriptor> descriptors = ArgumentCaptor.forClass(ToolDescriptor.class);

        new ToolsPlugin().start(context);

        verify(context, times(5)).handle(eq(ToolCallRequest.class), names.capture(), descriptors.capture(),
                ArgumentMatchers.<ExtensionHandler<ToolCallRequest, ToolCallResult>>any());
        for (int i = 0; i < names.getAllValues().size(); i++) {
            assertEquals(descriptors.getAllValues().get(i).getName(), names.getAllValues().get(i));
        }
    }

    @Test
    @DisplayName("工具清单里的名字不得重复：同名工具注册会互相覆盖")
    void start_should_registerDistinctNames() {
        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);

        new ToolsPlugin().start(context);

        verify(context, times(5)).handle(eq(ToolCallRequest.class), names.capture(), any(ToolDescriptor.class),
                ArgumentMatchers.<ExtensionHandler<ToolCallRequest, ToolCallResult>>any());
        assertEquals(5, names.getAllValues().stream().distinct().count());
    }

    @Test
    @DisplayName("单个工具注册时应把自身实例作为处理器交出")
    void register_should_bindToolInstance() {
        ReadFileTool tool = new ReadFileTool();

        tool.register(context);

        verify(context).handle(eq(ToolCallRequest.class), eq("read_file"), eq(tool.descriptor()), eq(tool));
    }
}
