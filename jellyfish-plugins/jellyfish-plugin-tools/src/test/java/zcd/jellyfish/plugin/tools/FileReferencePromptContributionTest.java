package zcd.jellyfish.plugin.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.PromptContribution;
import zcd.jellyfish.api.extension.PromptContributionRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileReferencePromptContribution} 的单元测试：约定必须真的告诉模型「去读文件」。
 * <p>
 * 这条约定是 {@code @} 不内联内容的前提——没有它，模型只会把 {@code @a.txt} 当成一段普通文字。
 *
 * @author zcd
 */
@DisplayName("FileReferencePromptContribution 约定贡献")
class FileReferencePromptContributionTest {

    @Test
    @DisplayName("贡献文本应说明 @路径 的含义并要求调用 read_file")
    void handle_should_explain_reference_and_read_file() {
        // When
        PromptContribution contribution = new FileReferencePromptContribution()
                .handle(new PromptContributionRequest("s-1"));

        // Then
        assertFalse(contribution.isEmpty());
        assertTrue(contribution.getText().contains("@"));
        assertTrue(contribution.getText().contains("read_file"));
    }

    @Test
    @DisplayName("会话标识缺失时也应给出约定：约定与会话无关")
    void handle_should_work_without_session() {
        // When
        PromptContribution contribution = new FileReferencePromptContribution()
                .handle(new PromptContributionRequest(null));

        // Then
        assertFalse(contribution.isEmpty());
    }
}
