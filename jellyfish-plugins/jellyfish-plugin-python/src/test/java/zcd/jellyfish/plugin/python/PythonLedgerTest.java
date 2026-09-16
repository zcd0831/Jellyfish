package zcd.jellyfish.plugin.python;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
@DisplayName("Python 脚本台账")
class PythonLedgerTest {

    @Test
    @DisplayName("空台账应说明原因，而不是只给一对零")
    void render_should_explainEmptiness_when_noScriptsAreLoaded() {
        String rendered = PythonLedger.empty().render("Python");

        assertTrue(rendered.contains("脚本 0 个"), rendered);
        assertTrue(rendered.contains("脚本目录为空"), rendered);
    }

    @Test
    @DisplayName("汇总行应带脚本数、能力数与问题数")
    void summary_should_countScriptsCapabilitiesAndIssues() {
        PythonLedger empty = PythonLedger.empty();

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

        String rendered = new PythonLedger(new java.util.ArrayList<zcd.jellyfish.script.ScriptPlugin>(),
                new java.util.ArrayList<zcd.jellyfish.script.ScriptRegistration>(),
                issues, null).render("Python");

        assertTrue(rendered.contains("问题 2 条"), rendered);
        assertTrue(rendered.contains("缺少 manifest.json"), rendered);
        assertTrue(rendered.contains("工具注册失败"), rendered);
    }
}
