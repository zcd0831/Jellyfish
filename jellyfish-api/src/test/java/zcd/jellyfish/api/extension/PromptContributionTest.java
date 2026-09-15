package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PromptContributionRequest} 与 {@link PromptContribution} 的单元测试。
 *
 * @author zcd
 */
class PromptContributionTest {

    @Test
    void contribution_should_keepText() {
        PromptContribution contribution = PromptContribution.of("上下文");

        assertEquals("上下文", contribution.getText());
        assertTrue(!contribution.isEmpty());
    }

    @Test
    void contribution_should_beEmpty_when_textBlankOrNull() {
        assertTrue(PromptContribution.of(null).isEmpty());
        assertTrue(PromptContribution.of("   ").isEmpty());
        assertNull(PromptContribution.of("   ").getText());
        assertTrue(PromptContribution.empty().isEmpty());
    }

    @Test
    void request_should_carrySessionIdAndBeTypeLevel() {
        PromptContributionRequest request = new PromptContributionRequest("s-1");

        assertEquals("s-1", request.getSessionId());
        assertNull(request.getRouteKey());
        assertEquals(PromptContribution.class, request.getResultType());
    }

    @Test
    void request_should_allowProcessLevel() {
        assertNull(new PromptContributionRequest(null).getSessionId());
    }
}
