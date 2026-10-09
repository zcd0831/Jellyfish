package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PermissionApprovalSettings} 的单元测试：钉住「缺省 120 秒」「{@code 0} = 永不超时」
 * 「负数按非法回退」「超过 3600 秒告警」四条口径。
 * <p>
 * {@code 0} 这一条与提问侧同口径但后果更重：审批是 fail-closed（等不到人按拒绝处理），
 * 永不超时把它换成「等不到人就一直卡住」，因此它必须<b>生效</b>（而不是被悄悄换成 120 秒），
 * 同时必须<b>告警</b>（否则与手滑写错无法区分）。
 *
 * @author zcd
 */
@DisplayName("PermissionApprovalSettings 审批配置")
class PermissionApprovalSettingsTest {

    @Test
    @DisplayName("不配置时使用缺省超时，且不产生告警")
    void constructor_should_use_default_when_absent() {
        // When
        PermissionApprovalSettings settings = new PermissionApprovalSettings(null);

        // Then
        assertEquals(PermissionApprovalSettings.DEFAULT_APPROVAL_TIMEOUT_SECONDS,
                settings.getApprovalTimeoutSeconds());
        assertFalse(settings.isInfinite());
        assertTrue(settings.isDefault());
        assertTrue(settings.warnings().isEmpty(), settings.warnings().toString());
    }

    @Test
    @DisplayName("显式配置的正数生效，且不再算缺省")
    void constructor_should_accept_positive_value() {
        // When
        PermissionApprovalSettings settings = new PermissionApprovalSettings(30);

        // Then
        assertEquals(30, settings.getApprovalTimeoutSeconds());
        assertFalse(settings.isInfinite());
        assertFalse(settings.isDefault());
        assertTrue(settings.warnings().isEmpty(), settings.warnings().toString());
    }

    @Test
    @DisplayName("写 0 表示永不超时：取值就是 0，但要靠 isInfinite 区分「无限」与「零秒」")
    void constructor_should_treat_zero_as_never_timeout() {
        // When
        PermissionApprovalSettings settings =
                new PermissionApprovalSettings(PermissionApprovalSettings.INFINITE_TIMEOUT_SECONDS);

        // Then
        assertTrue(settings.isInfinite());
        assertEquals(PermissionApprovalSettings.INFINITE_TIMEOUT_SECONDS, settings.getApprovalTimeoutSeconds());
        assertFalse(settings.isDefault(), "永不超时是刻意配的，不能算「什么都没配」");
        assertEquals(1, settings.warnings().size(), settings.warnings().toString());
        assertTrue(settings.warnings().get(0).contains("approvalTimeoutSeconds=0"),
                settings.warnings().get(0));
        assertTrue(settings.warnings().get(0).contains("fail-closed"), settings.warnings().get(0));
    }

    @ParameterizedTest
    @DisplayName("负数按非法处理：回退缺省值并告警")
    @ValueSource(ints = {-1, -120})
    void constructor_should_fall_back_to_default_when_negative(int value) {
        // When
        PermissionApprovalSettings settings = new PermissionApprovalSettings(value);

        // Then
        assertEquals(PermissionApprovalSettings.DEFAULT_APPROVAL_TIMEOUT_SECONDS,
                settings.getApprovalTimeoutSeconds());
        assertFalse(settings.isInfinite());
        assertTrue(settings.isDefault());
        assertEquals(1, settings.warnings().size(), settings.warnings().toString());
        assertTrue(settings.warnings().get(0).contains("approvalTimeoutSeconds=" + value),
                settings.warnings().get(0));
        assertTrue(settings.warnings().get(0).contains("必须 ≥0"), settings.warnings().get(0));
    }

    @Test
    @DisplayName("超过 3600 秒照旧生效，但要告警并提示「真要永不超时应写 0」")
    void constructor_should_warn_when_absurdly_large() {
        // When
        PermissionApprovalSettings settings = new PermissionApprovalSettings(99999);

        // Then
        assertEquals(99999, settings.getApprovalTimeoutSeconds());
        assertFalse(settings.isInfinite());
        assertEquals(1, settings.warnings().size(), settings.warnings().toString());
        assertTrue(settings.warnings().get(0).contains("99999"), settings.warnings().get(0));
        assertTrue(settings.warnings().get(0).contains("写 0"), settings.warnings().get(0));
    }

    @Test
    @DisplayName("无参构造等价于全缺省，且不产生告警")
    void default_constructor_should_equal_defaults() {
        // When
        PermissionApprovalSettings settings = new PermissionApprovalSettings();

        // Then
        assertEquals(new PermissionApprovalSettings(null).getApprovalTimeoutSeconds(),
                settings.getApprovalTimeoutSeconds());
        assertTrue(settings.isDefault());
        assertTrue(settings.warnings().isEmpty(), settings.warnings().toString());
    }
}
