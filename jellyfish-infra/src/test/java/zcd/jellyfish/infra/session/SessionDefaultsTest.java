package zcd.jellyfish.infra.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionDefaults} 的单元测试：锁住「初始为空」「各字段独立更新」与「快照是整体替换」。
 *
 * @author zcd
 */
@DisplayName("SessionDefaults 待生效默认值")
class SessionDefaultsTest {

    /** 被测对象。 */
    private final SessionDefaults defaults = new SessionDefaults();

    @Test
    @DisplayName("初始三项全空：等于「全部跟随更下层的默认」")
    void snapshot_should_beEmptyByDefault() {
        // When
        SessionDefaults.Values values = defaults.snapshot();

        // Then
        assertTrue(values.isEmpty());
        assertNull(values.getAgentId());
        assertNull(values.getProvider());
        assertNull(values.getModel());
    }

    @Test
    @DisplayName("只设某一项不牵连其它项：设了模型不该顺手把 agent 钉死")
    void setModel_should_notTouchOtherFields() {
        // Given
        defaults.setAgentId("coder");

        // When
        defaults.setModel("openai", "gpt-4o");

        // Then
        assertEquals("coder", defaults.snapshot().getAgentId());
        assertEquals("openai", defaults.snapshot().getProvider());
        assertEquals("gpt-4o", defaults.snapshot().getModel());
        assertFalse(defaults.snapshot().isEmpty());
    }

    @Test
    @DisplayName("每次更新换出一个新快照，已取到的快照不被后续写入改动")
    void snapshot_should_beImmutableWhenUpdatedLater() {
        // Given
        defaults.setModel("openai", "gpt-4o");
        SessionDefaults.Values before = defaults.snapshot();

        // When
        defaults.setModel("ollama", "llama3");

        // Then：快照是值对象，持它的一方不会被「稍后的一次修改」偷改
        assertEquals("gpt-4o", before.getModel());
        assertEquals("llama3", defaults.snapshot().getModel());
    }
}
