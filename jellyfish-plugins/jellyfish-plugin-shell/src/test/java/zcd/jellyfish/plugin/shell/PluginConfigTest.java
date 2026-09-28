package zcd.jellyfish.plugin.shell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.plugin.PluginContext;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link PluginConfig} 的单元测试。
 * <p>
 * 这里钉住的口径是「<b>非法值回退缺省、不阻断启动</b>」：配置写错不该让 shell 工具整个消失——
 * 那会把「一个参数写成了字符串」升级成「模型忽然没有命令行能力」。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PluginConfig")
class PluginConfigTest {

    @Test
    @DisplayName("缺省值：120 秒超时、1800 秒上限、静默关闭、白名单为空")
    void from_should_useDefaults_when_nothingConfigured() {
        PluginConfig config = PluginConfig.from(null);

        assertEquals(120, config.timeoutSeconds());
        assertEquals(1800, config.maxTimeoutSeconds());
        assertEquals(0, config.idleTimeoutSeconds());
        assertTrue(config.environment().isEmpty());
        assertTrue(config.extraSensitivePatterns().isEmpty());
        assertTrue(config.allowedCommands().isEmpty());
    }

    @Test
    @DisplayName("上下文取不到配置段时按缺省启动")
    void from_should_useDefaults_when_contextThrows() {
        PluginContext context = mock(PluginContext.class);
        when(context.configuration()).thenThrow(new IllegalStateException("配置段坏了"));

        assertEquals(120, PluginConfig.from(context).timeoutSeconds());
    }

    @Test
    @DisplayName("非法整数回退缺省——不阻断启动")
    void from_should_fallBack_when_valueNotInteger() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("timeoutSeconds", "很久");

        assertEquals(120, PluginConfig.from(context(values)).timeoutSeconds());
    }

    @Test
    @DisplayName("超过硬上限的值被钳制")
    void from_should_clamp_when_valueExceedsHardLimit() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("maxTimeoutSeconds", Integer.valueOf(999_999));

        assertEquals(PluginConfig.HARD_MAX_TIMEOUT_SECONDS,
                PluginConfig.from(context(values)).maxTimeoutSeconds());
    }

    @Test
    @DisplayName("超时小于等于 0 时回退缺省，而不是变成「不超时」")
    void from_should_fallBack_when_timeoutNotPositive() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("timeoutSeconds", Integer.valueOf(0));

        assertEquals(120, PluginConfig.from(context(values)).timeoutSeconds());
    }

    @Test
    @DisplayName("负数静默超时回退到关闭")
    void from_should_fallBack_when_idleTimeoutNegative() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("idleTimeoutSeconds", Integer.valueOf(-5));

        assertEquals(0, PluginConfig.from(context(values)).idleTimeoutSeconds());
    }

    @Test
    @DisplayName("数字字符串被接受（配置文件里手写的值常常带引号）")
    void from_should_acceptNumericStrings() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("timeoutSeconds", "30");
        values.put("idleTimeoutSeconds", "45");

        PluginConfig config = PluginConfig.from(context(values));

        assertEquals(30, config.timeoutSeconds());
        assertEquals(45, config.idleTimeoutSeconds());
    }

    @Test
    @DisplayName("环境变量映射被原样读出，空值项被丢弃")
    void from_should_parseEnvironment() {
        Map<String, Object> environment = new HashMap<String, Object>();
        environment.put("CUSTOM", "1");
        environment.put("EMPTY", null);
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("environment", environment);

        Map<String, String> parsed = PluginConfig.from(context(values)).environment();

        assertEquals("1", parsed.get("CUSTOM"));
        assertTrue(!parsed.containsKey("EMPTY"));
    }

    @Test
    @DisplayName("environment 不是对象时按空处理")
    void from_should_tolerateNonMapEnvironment() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("environment", "CUSTOM=1");

        assertTrue(PluginConfig.from(context(values)).environment().isEmpty());
    }

    @Test
    @DisplayName("白名单与追加脱敏模式被读出，多余项被丢弃")
    void from_should_parseLists() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("allowedCommands", Arrays.asList("git status", "  ", "ls"));
        values.put("sensitivePatterns", Collections.singletonList("*INTERNAL*"));

        PluginConfig config = PluginConfig.from(context(values));

        assertEquals(Arrays.asList("git status", "ls"), config.allowedCommands());
        assertEquals(Collections.singletonList("*INTERNAL*"), config.extraSensitivePatterns());
    }

    @Test
    @DisplayName("列表不是数组时按空处理")
    void from_should_tolerateNonListValues() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("allowedCommands", "git");

        assertTrue(PluginConfig.from(context(values)).allowedCommands().isEmpty());
    }

    @Test
    @DisplayName("命令策略段被透传，且带上白名单")
    void from_should_parseCommandPolicy() {
        Map<String, Object> policy = new HashMap<String, Object>();
        policy.put("enabled", Boolean.FALSE);
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("allowedCommands", Collections.singletonList("ls"));
        values.put("commandPolicy", policy);

        PluginConfig config = PluginConfig.from(context(values));

        // 分类器关掉了，但白名单仍然生效：两者是两件事。
        // 在白名单里且又只读 → 无异议；不在白名单里 → 直接拒绝（默认拒绝语义）
        assertTrue(config.commandPolicy().verdict("ls -la").isAbstain());
        assertTrue(config.commandPolicy().verdict("pwd").isDenied());
        assertTrue(config.commandPolicy().verdict("curl x").isDenied());
    }

    /**
     * 造一个只返回配置段的插件上下文。
     *
     * @param configuration 配置段
     * @return 插件上下文
     */
    private static PluginContext context(Map<String, Object> configuration) {
        PluginContext context = mock(PluginContext.class);
        when(context.configuration()).thenReturn(configuration);
        return context;
    }
}
