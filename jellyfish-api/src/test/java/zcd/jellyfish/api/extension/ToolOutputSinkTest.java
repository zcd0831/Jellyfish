package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ToolOutputSink} 缺省实例的单元测试：锁住「不使用捕获通道即完全无副作用」这条语义。
 *
 * @author zcd
 */
@DisplayName("ToolOutputSink 不使用捕获通道的实例")
class ToolOutputSinkTest {

    @Test
    void noop_should_swallow_writes_and_summary() {
        // When：绝大多数工具不参与捕获，写入必须被静默吞掉而不是抛异常
        ToolOutputSink.NOOP.write("line-1");
        ToolOutputSink.NOOP.write(null);
        ToolOutputSink.NOOP.summary("cwd: /tmp");

        // Then
        assertNull(ToolOutputSink.NOOP.finish());
    }

    @Test
    void noop_finish_should_return_null_even_after_writes() {
        // Given：写入后再收尾
        ToolOutputSink.NOOP.write("x");

        // Then：调用点据此判断「本次没有走捕获路径」，从而让事后截断逻辑照常生效
        assertNull(ToolOutputSink.NOOP.finish());
    }
}
