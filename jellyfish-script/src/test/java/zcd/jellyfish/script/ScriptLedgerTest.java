package zcd.jellyfish.script;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.script.codec.ExtensionCodecs;

import java.nio.file.Paths;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脚本台账渲染的单元测试。
 * <p>
 * 这里钉住的是一件容易被当成「美化」而忽略的事：<b>问题必须逐条列出来</b>。
 * 「有 3 个问题」而不说是什么问题，等于把定位工作又推回给作者；而清单漂移的现场特征恰恰是
 * 「工具没出现」，那时人唯一的抓手就是这段输出。
 *
 * @author zcd
 */
@DisplayName("脚本台账")
class ScriptLedgerTest {

    @Test
    @DisplayName("空台账应说明原因，而不是只给一对零")
    void render_should_explainEmptiness_when_noScriptsAreLoaded() {
        String rendered = ScriptLedger.empty().render("Python");

        assertTrue(rendered.contains("脚本 0 个"), rendered);
        assertTrue(rendered.contains("脚本目录为空"), rendered);
    }

    @Test
    @DisplayName("汇总行应带脚本数、能力数与问题数")
    void summary_should_countScriptsCapabilitiesAndIssues() {
        ScriptLedger empty = ScriptLedger.empty();

        assertEquals("scripts=0 capabilities=0 issues=0", empty.summary());
        assertEquals(0, empty.scriptCount());
        assertEquals(0, empty.capabilityCount());
        assertTrue(empty.plugins().isEmpty());
        assertTrue(empty.issues().isEmpty());
    }

    @Test
    @DisplayName("应逐条列出问题，而不是只报数量")
    void render_should_listEveryIssue() {
        java.util.List<String> issues = new java.util.ArrayList<String>();
        issues.add("清单: jira: 缺少 manifest.json");
        issues.add("注册: jira: 工具注册失败 x: 已注册");

        String rendered = new ScriptLedger(new java.util.ArrayList<ScriptPlugin>(),
                new java.util.ArrayList<ScriptRegistration>(),
                issues, null, null, null).render("Python");

        assertTrue(rendered.contains("问题 2 条"), rendered);
        assertTrue(rendered.contains("缺少 manifest.json"), rendered);
        assertTrue(rendered.contains("工具注册失败"), rendered);
    }

    @Test
    @DisplayName("熔断态应单独成行；没有记录时说「尚未发生调用」而不是省略")
    void render_should_showCircuitState() {
        CircuitBreakingScriptCaller callers = new CircuitBreakingScriptCaller(
                (plugin, type, request) -> {
                    throw new JellyfishException("脚本炸了");
                },
                CircuitBreakerSettings.builder().failuresToOpen(1).build(), null);
        ScriptPlugin jira = scriptOf("jira");
        assertThrows(JellyfishException.class, () -> callers.call(jira, "tool", null));

        String rendered = new ScriptLedger(new ArrayList<ScriptPlugin>(), new ArrayList<ScriptRegistration>(),
                new ArrayList<String>(), null, callers, null).render("Python");

        assertEquals(ScriptCircuitBreaker.State.OPEN, callers.stateOf("jira"));
        assertTrue(rendered.contains("熔断：jira 熔断中"), rendered);
    }

    @Test
    @DisplayName("从未调用过的脚本不该出现在熔断行里")
    void render_should_sayNotCalledYet_whenNoCallHappened() {
        CircuitBreakingScriptCaller callers = new CircuitBreakingScriptCaller(
                (plugin, type, request) -> null, CircuitBreakerSettings.defaults(), null);

        String rendered = new ScriptLedger(new ArrayList<ScriptPlugin>(), new ArrayList<ScriptRegistration>(),
                new ArrayList<String>(), null, callers, null).render("Python");

        assertTrue(rendered.contains("熔断：尚未发生调用"), rendered);
        assertTrue(callers.states().isEmpty());
    }

    /**
     * 造一个只用于熔断建档的脚本。
     * <p>
     * 清单正文里没有声明任何能力：这条用例关心的是「以谁的名义记账」，不是注册。
     *
     * @param id 脚本标识
     * @return 脚本
     */
    private static ScriptPlugin scriptOf(String id) {
        return new ScriptPlugin(id, Paths.get("/tmp/scripts").resolve(id),
                ScriptManifest.parse("{\"entry\":\"main.py\"}", id, ExtensionCodecs.DEFAULTS));
    }
}
