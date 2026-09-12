package zcd.jellyfish.infra.llm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LlmTool} 的单元测试，重点覆盖空值兜底与不可变性。
 *
 * @author zcd
 */
class LlmToolTest {

    @Test
    void getParameters_should_return_empty_map_when_parameters_is_null() {
        // Given
        LlmTool tool = new LlmTool("name", "desc", null, null);

        // Then
        assertTrue(tool.getParameters().isEmpty());
        assertTrue(tool.getRequired().isEmpty());
    }

    @Test
    void getParameters_should_be_unmodifiable_when_constructed() {
        // Given
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("q", "string");
        LlmTool tool = new LlmTool("name", "desc", parameters, null);

        // Then
        assertThrows(UnsupportedOperationException.class, () -> tool.getParameters().put("x", "y"));
    }

    @Test
    void getRequired_should_be_unmodifiable_when_constructed() {
        // Given
        List<String> required = new ArrayList<>();
        required.add("q");
        LlmTool tool = new LlmTool("name", "desc", null, required);

        // Then
        assertThrows(UnsupportedOperationException.class, () -> tool.getRequired().add("z"));
    }

    @Test
    void getParameters_should_not_reflect_external_mutation_when_source_list_changes() {
        // Given
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("q", "string");
        LlmTool tool = new LlmTool("name", "desc", parameters, null);

        // When
        parameters.put("later", "added");

        // Then
        assertEquals(1, tool.getParameters().size());
    }
}
