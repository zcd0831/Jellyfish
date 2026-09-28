package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolCallRequest} 的单元测试：只覆盖「调用期设施」这一新增语义。
 * <p>
 * 路由键、会话标识与参数只读性由 {@code ExtensionRequestTest} 覆盖，这里不重复。
 *
 * @author zcd
 */
@DisplayName("ToolCallRequest 调用期设施")
class ToolCallRequestTest {

    @Test
    void getCancellationToken_should_return_provided_token() {
        // Given
        CancellationToken token = new CancellationToken() {

            @Override
            public boolean isCancelled() {
                return true;
            }

            @Override
            public void onCancel(Runnable callback) {
                callback.run();
            }
        };

        // When
        ToolCallRequest request = new ToolCallRequest("shell", null, "s-1", token, null);

        // Then
        assertSame(token, request.getCancellationToken());
        assertTrue(request.getCancellationToken().isCancelled());
    }

    @Test
    void getOutputSink_should_return_provided_sink() {
        // Given
        ToolOutputSink sink = new ToolOutputSink() {

            @Override
            public void write(String chunk) {
                // 测试替身：无需记录
            }

            @Override
            public void summary(String text) {
                // 测试替身：无需记录
            }

            @Override
            public String finish() {
                return "done";
            }
        };

        // When
        ToolCallRequest request = new ToolCallRequest("shell", null, "s-1", null, sink);

        // Then
        assertSame(sink, request.getOutputSink());
        assertEquals("done", request.getOutputSink().finish());
    }

    @Test
    void constructor_should_default_token_and_sink_when_null() {
        // When：显式传 null 与「不传」同义，工具拿到的必须是非空实例
        ToolCallRequest request = new ToolCallRequest("shell", null, "s-1", null, null);

        // Then
        assertSame(CancellationToken.NONE, request.getCancellationToken());
        assertSame(ToolOutputSink.NOOP, request.getOutputSink());
        assertFalse(request.getCancellationToken().isCancelled());
    }

    @Test
    void legacy_constructors_should_default_token_and_sink() {
        // When：保留旧构造器是为了源码兼容，既有工具一行不用改
        ToolCallRequest withSession = new ToolCallRequest("read_file", null, "s-1");
        ToolCallRequest processLevel = new ToolCallRequest("read_file", null);

        // Then
        assertSame(CancellationToken.NONE, withSession.getCancellationToken());
        assertSame(ToolOutputSink.NOOP, withSession.getOutputSink());
        assertSame(CancellationToken.NONE, processLevel.getCancellationToken());
        assertSame(ToolOutputSink.NOOP, processLevel.getOutputSink());
    }

    @Test
    void getArguments_should_stay_unmodifiable_in_new_constructor() {
        // Given
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("command", "ls");

        // When
        ToolCallRequest request = new ToolCallRequest("shell", arguments, "s-1",
                CancellationToken.NONE, ToolOutputSink.NOOP);
        arguments.put("command", "rm -rf /");

        // Then
        assertEquals("ls", request.getArguments().get("command"));
        Map<String, Object> empty = Collections.emptyMap();
        assertEquals(empty.size(), new ToolCallRequest("shell", empty, "s-1").getArguments().size());
    }
}
