package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolCallResult} 的元数据契约：它跨的是插件边界，因此「不可变」与「判空」都要钉住。
 *
 * @author zcd
 */
@DisplayName("ToolCallResult 元数据")
class ToolCallResultTest {

    @Test
    @DisplayName("不给元数据时是空映射，调用方不必判空")
    void getMetadata_should_defaultToEmptyMap() {
        assertTrue(new ToolCallResult("shell", "out").getMetadata().isEmpty());
        assertTrue(new ToolCallResult("shell", "out", null).getMetadata().isEmpty());
        assertTrue(new ToolCallResult("shell", "out", new LinkedHashMap<String, Object>()).getMetadata().isEmpty());
    }

    @Test
    @DisplayName("元数据在构造期拷贝且不可变：工具构造完再改自己的映射不该影响结果")
    void getMetadata_should_beUnmodifiableCopy() {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put(ToolMetadata.KEY_EXIT_CODE, Integer.valueOf(1));
        ToolCallResult result = new ToolCallResult("shell", "out", metadata);

        metadata.put("late", "value");

        assertEquals(1, result.getMetadata().size());
        assertEquals(Integer.valueOf(1), result.getMetadata().get(ToolMetadata.KEY_EXIT_CODE));
        assertThrows(UnsupportedOperationException.class, () -> result.getMetadata().put("x", "y"));
    }

    @Test
    @DisplayName("输出与元数据各自保留：模型读文本，界面读字段")
    void getOutput_should_keepValue_alongsideMetadata() {
        ToolCallResult result = new ToolCallResult("shell", "cwd: /x · exit: 1",
                java.util.Collections.<String, Object>singletonMap(ToolMetadata.KEY_EXIT_CODE, Integer.valueOf(1)));

        assertEquals("cwd: /x · exit: 1", result.getOutput());
        assertEquals(Integer.valueOf(1), result.getMetadata().get(ToolMetadata.KEY_EXIT_CODE));
    }
}
