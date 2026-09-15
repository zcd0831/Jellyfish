package zcd.jellyfish.tui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link TuiTerminal} 的单元测试。
 * <p>
 * 断言分成两类：<b>不依赖运行环境</b>的（逃生门开关语义）无条件断言；
 * <b>依赖运行环境</b>的（无终端时的判定）用 {@code assumeTrue} 保护——
 * 万一在可交互终端里跑测试，跳过而不是误报失败。
 *
 * @author zcd
 */
@DisplayName("TuiTerminal 终端前置条件判定")
class TuiTerminalTest {

    @AfterEach
    void clearSkipProperty() {
        System.clearProperty(TuiTerminal.SKIP_CHECK_PROPERTY);
    }

    @Test
    @DisplayName("设置逃生门后应判定为可交互，即使没有终端")
    void isInteractive_should_beTrue_when_skipPropertySet() {
        System.setProperty(TuiTerminal.SKIP_CHECK_PROPERTY, "true");

        assertTrue(TuiTerminal.isInteractive());
    }

    @Test
    @DisplayName("设置逃生门后不应再报出原因")
    void unsupportedReason_should_beEmpty_when_skipPropertySet() {
        System.setProperty(TuiTerminal.SKIP_CHECK_PROPERTY, "true");

        assertFalse(TuiTerminal.unsupportedReason().isPresent());
    }

    @Test
    @DisplayName("逃生门取值不是 true 时不应生效")
    void isInteractive_should_ignoreSkipProperty_when_valueIsNotTrue() {
        System.setProperty(TuiTerminal.SKIP_CHECK_PROPERTY, "yes");
        assumeTrue(System.console() == null, "当前测试 JVM 有可交互终端，无法验证未开启逃生门的判定");

        assertFalse(TuiTerminal.isInteractive());
    }

    @Test
    @DisplayName("没有终端且未开逃生门时应给出原因")
    void unsupportedReason_should_bePresent_when_noConsoleAndNoSkip() {
        assumeTrue(System.console() == null, "当前测试 JVM 有可交互终端，无法验证无终端场景");

        Optional<String> reason = TuiTerminal.unsupportedReason();

        assertTrue(reason.isPresent());
    }

    @Test
    @DisplayName("原因文案应可执行：指出改用 -cli 并给出逃生门开关名")
    void unsupportedReason_should_mentionFallbacks_when_noConsole() {
        assumeTrue(System.console() == null, "当前测试 JVM 有可交互终端，无法验证无终端场景");

        String reason = TuiTerminal.unsupportedReason().orElseThrow(AssertionError::new);

        // 文案是用户唯一的线索，必须在没有终端时也能直接照做
        assertTrue(reason.contains("-cli"), "应提示改用 -cli，实际：" + reason);
        assertTrue(reason.contains(TuiTerminal.SKIP_CHECK_PROPERTY),
                "应给出逃生门开关名，实际：" + reason);
    }
}
