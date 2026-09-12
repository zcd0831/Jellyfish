package zcd.jellyfish.infra.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StreamToolCallAccumulator} 的单元测试：覆盖按 index 合并、快照与顺序。
 *
 * @author zcd
 */
class StreamToolCallAccumulatorTest {

    @Test
    void merge_should_accumulate_arguments_when_same_index_appears_multiple_times() {
        // Given
        StreamToolCallAccumulator accumulator = new StreamToolCallAccumulator();

        // When
        accumulator.merge(0, "id-1", "search", "{\"a\":");
        LlmToolCall merged = accumulator.merge(0, null, null, "1}");

        // Then
        assertEquals(1, accumulator.toList().size());
        assertEquals("id-1", merged.getId());
        assertEquals("search", merged.getName());
        assertEquals("{\"a\":1}", merged.getArguments());
    }

    @Test
    void merge_should_keep_previous_id_and_name_when_delta_is_null() {
        // Given
        StreamToolCallAccumulator accumulator = new StreamToolCallAccumulator();
        accumulator.merge(0, "id-1", "search", "a");

        // When
        LlmToolCall merged = accumulator.merge(0, null, null, "b");

        // Then
        assertEquals("id-1", merged.getId());
        assertEquals("search", merged.getName());
        assertEquals("ab", merged.getArguments());
    }

    @Test
    void merge_should_keep_previous_arguments_when_delta_is_null() {
        // Given
        StreamToolCallAccumulator accumulator = new StreamToolCallAccumulator();
        accumulator.merge(0, "id-1", "search", "abc");

        // When
        LlmToolCall merged = accumulator.merge(0, null, null, null);

        // Then
        assertEquals("abc", merged.getArguments());
    }

    @Test
    void merge_should_create_separate_calls_when_index_differs() {
        // Given
        StreamToolCallAccumulator accumulator = new StreamToolCallAccumulator();

        // When
        accumulator.merge(0, "id-0", "f0", "{}");
        accumulator.merge(1, "id-1", "f1", "{}");

        // Then
        assertEquals(2, accumulator.toList().size());
    }

    @Test
    void merge_should_generate_distinct_keys_when_index_is_null() {
        // Given
        StreamToolCallAccumulator accumulator = new StreamToolCallAccumulator();

        // When
        LlmToolCall first = accumulator.merge(null, "id-1", "f1", "{}");
        LlmToolCall second = accumulator.merge(null, "id-2", "f2", "{}");

        // Then
        assertEquals(2, accumulator.toList().size());
        assertFalse(first.getIndex().equals(second.getIndex()));
    }

    @Test
    void add_should_store_complete_call_and_return_snapshot() {
        // Given
        StreamToolCallAccumulator accumulator = new StreamToolCallAccumulator();

        // When
        LlmToolCall added = accumulator.add("id-1", "search", "{\"a\":1}");

        // Then
        assertEquals("id-1", added.getId());
        assertEquals("search", added.getName());
        assertEquals("{\"a\":1}", added.getArguments());
        assertEquals(1, accumulator.toList().size());
    }

    @Test
    void add_should_treat_null_arguments_as_empty_when_added() {
        // Given
        StreamToolCallAccumulator accumulator = new StreamToolCallAccumulator();

        // When
        LlmToolCall added = accumulator.add("id", "name", null);

        // Then
        assertEquals("", added.getArguments());
        assertEquals(1, accumulator.toList().size());
    }

    @Test
    void isEmpty_should_reflect_current_state() {
        // Given
        StreamToolCallAccumulator accumulator = new StreamToolCallAccumulator();

        // Then
        assertTrue(accumulator.isEmpty());

        // When
        accumulator.merge(0, "id", "name", "{}");

        // Then
        assertFalse(accumulator.isEmpty());
    }

    @Test
    void toList_should_preserve_insertion_order() {
        // Given
        StreamToolCallAccumulator accumulator = new StreamToolCallAccumulator();

        // When
        accumulator.merge(1, "id-1", "f1", "{}");
        accumulator.merge(0, "id-0", "f0", "{}");

        // Then
        assertEquals("id-1", accumulator.toList().get(0).getId());
        assertEquals("id-0", accumulator.toList().get(1).getId());
    }
}
