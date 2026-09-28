package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link InputReferenceChoice} 的单元测试：验证插入文本回退与标签校验。
 *
 * @author zcd
 */
class InputReferenceChoiceTest {

    @Test
    void insertText_should_fall_back_to_label_when_missing() {
        // When
        InputReferenceChoice choice = new InputReferenceChoice("src/", null, "目录");

        // Then
        assertEquals("src/", choice.getLabel());
        assertEquals("src/", choice.getInsertText());
        assertEquals("目录", choice.getDetail());
    }

    @Test
    void insertText_should_keep_explicit_value() {
        // When
        InputReferenceChoice choice = new InputReferenceChoice("my file.txt", "my\\ file.txt", "12 字节");

        // Then
        assertEquals("my file.txt", choice.getLabel());
        assertEquals("my\\ file.txt", choice.getInsertText());
    }

    @Test
    void constructor_should_reject_blank_label() {
        // When / Then
        assertThrows(JellyfishException.class, () -> new InputReferenceChoice(null, null, null));
        assertThrows(JellyfishException.class, () -> new InputReferenceChoice("  ", null, null));
    }
}
