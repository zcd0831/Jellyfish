package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InputReferenceResult} 的单元测试：验证空值语义、顺序保持与不可变性。
 *
 * @author zcd
 */
class InputReferenceResultTest {

    @Test
    void empty_should_have_no_choices_and_be_the_same_instance() {
        // When / Then
        assertTrue(InputReferenceResult.empty().getChoices().isEmpty());
        assertSame(InputReferenceResult.empty(), InputReferenceResult.empty());
    }

    @Test
    void of_null_or_empty_should_equal_empty() {
        // When / Then
        assertSame(InputReferenceResult.empty(), InputReferenceResult.of(null));
        assertSame(InputReferenceResult.empty(), InputReferenceResult.of(new ArrayList<InputReferenceChoice>()));
    }

    @Test
    void of_should_keep_order_and_reject_mutation() {
        // Given
        InputReferenceChoice first = new InputReferenceChoice("a.txt", null, null);
        InputReferenceChoice second = new InputReferenceChoice("b/", null, null);
        List<InputReferenceChoice> source = new ArrayList<InputReferenceChoice>(Arrays.asList(first, second));

        // When
        InputReferenceResult result = InputReferenceResult.of(source);
        source.clear();

        // Then
        assertEquals(2, result.getChoices().size());
        assertEquals("a.txt", result.getChoices().get(0).getLabel());
        assertEquals("b/", result.getChoices().get(1).getLabel());
        assertThrows(UnsupportedOperationException.class,
                () -> result.getChoices().add(new InputReferenceChoice("c", null, null)));
    }
}
