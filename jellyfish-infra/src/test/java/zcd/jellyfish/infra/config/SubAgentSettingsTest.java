package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SubAgentSettings} 的单元测试：验证四项参数的缺省回退、显式 0 的保留与反序列化。
 * <p>
 * 重点在「缺省」与「显式 0」必须能区分：{@code maxDepth = 0} 的语义是「禁止委派」，
 * 若把它当成未配置而回退缺省，用户就再也关不掉嵌套。
 *
 * @author zcd
 */
class SubAgentSettingsTest {

    @Test
    void constructor_should_use_defaults_when_not_configured() {
        // When
        SubAgentSettings settings = new SubAgentSettings();

        // Then
        assertTrue(settings.isEnabled());
        assertEquals(SubAgentSettings.DEFAULT_MAX_DEPTH, settings.getMaxDepth());
        assertEquals(SubAgentSettings.DEFAULT_MAX_SPAWNS_PER_TURN, settings.getMaxSpawnsPerTurn());
        assertEquals(SubAgentSettings.DEFAULT_MAX_ROUNDS, settings.getMaxRounds());
        assertTrue(settings.isDefault());
    }

    @Test
    void constructor_should_keep_explicit_zero_max_depth() {
        // When：0 是「禁止委派」的合法取值，不能被当成未配置
        SubAgentSettings settings = new SubAgentSettings(null, 0, null, null, null, null, null, null);

        // Then
        assertEquals(0, settings.getMaxDepth());
        assertFalse(settings.isDefault());
    }

    @Test
    void constructor_should_fall_back_when_max_depth_negative() {
        // When
        SubAgentSettings settings = new SubAgentSettings(null, -1, null, null, null, null, null, null);

        // Then
        assertEquals(SubAgentSettings.DEFAULT_MAX_DEPTH, settings.getMaxDepth());
    }

    @Test
    void constructor_should_keep_explicit_disabled() {
        // When
        SubAgentSettings settings = new SubAgentSettings(false, null, null, null, null, null, null, null);

        // Then
        assertFalse(settings.isEnabled());
        assertFalse(settings.isDefault());
    }

    @Test
    void constructor_should_fall_back_when_max_spawns_not_positive() {
        // When
        SubAgentSettings zero = new SubAgentSettings(null, null, 0, null, null, null, null, null);
        SubAgentSettings negative = new SubAgentSettings(null, null, -3, null, null, null, null, null);

        // Then
        assertEquals(SubAgentSettings.DEFAULT_MAX_SPAWNS_PER_TURN, zero.getMaxSpawnsPerTurn());
        assertEquals(SubAgentSettings.DEFAULT_MAX_SPAWNS_PER_TURN, negative.getMaxSpawnsPerTurn());
    }

    @Test
    void constructor_should_fall_back_when_max_rounds_not_positive() {
        // When
        SubAgentSettings settings = new SubAgentSettings(null, null, null, 0, null, null, null, null);

        // Then
        assertEquals(SubAgentSettings.DEFAULT_MAX_ROUNDS, settings.getMaxRounds());
    }

    @Test
    void deserialization_should_bind_all_fields() {
        // Given
        String json = "{\"enabled\":false,\"maxDepth\":1,\"maxSpawnsPerTurn\":4,\"maxRounds\":3}";

        // When
        SubAgentSettings settings = ObjectMapperWrapper.readValue(json, SubAgentSettings.class);

        // Then
        assertFalse(settings.isEnabled());
        assertEquals(1, settings.getMaxDepth());
        assertEquals(4, settings.getMaxSpawnsPerTurn());
        assertEquals(3, settings.getMaxRounds());
    }

    @Test
    void deserialization_should_use_defaults_for_empty_object() {
        // When
        SubAgentSettings settings = ObjectMapperWrapper.readValue("{}", SubAgentSettings.class);

        // Then
        assertTrue(settings.isDefault());
    }
}
