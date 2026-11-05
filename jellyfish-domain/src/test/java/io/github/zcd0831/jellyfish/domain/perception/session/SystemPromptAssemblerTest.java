package io.github.zcd0831.jellyfish.domain.perception.session;

import io.github.zcd0831.jellyfish.domain.action.agent.PromptTemplate;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 规则出处：04-perception §4.9.3。 */
class SystemPromptAssemblerTest {

    @Test
    void 渲染提示词并原样带出工具与技能() {
        Map<String, String> values = new HashMap<>();
        values.put("role", "审查员");
        List<ToolSchemaSnapshot> tools = Arrays.asList(
                new ToolSchemaSnapshot("read_file", "读文件", "{}", "文件内容"));
        List<SkillSummarySnapshot> skills = Arrays.asList(new SkillSummarySnapshot("code-review", "代码评审", "当需要评审时"));

        SystemPromptSnapshot snapshot = new SystemPromptAssembler().assemble(AgentSnapshotInput
                .builder(AgentId.of("builtin.main"))
                .basePrompt(new PromptTemplate("你是 ${role}。", Arrays
                .asList("role"))).promptVariables(values).activeTools(tools).activeSkills(skills).build());

        assertEquals("你是 审查员。", snapshot.getRenderedPrompt());
        assertEquals(1, snapshot.getToolList().size());
        assertEquals("code-review", snapshot.getSkillList().get(0).getName());
    }

    @Test
    void SOP引用未绑定工具时给出告警清单() {
        List<ToolSchemaSnapshot> tools = Arrays.asList(
                new ToolSchemaSnapshot("read_file", "读文件", "{}", "文件内容"));

        List<String> unbound = new SystemPromptAssembler().unboundToolReferences(AgentSnapshotInput
                .builder(AgentId.of("builtin.main"))
                .activeTools(tools).sopReferencedToolNames(Arrays.asList("read_file", "git_diff")).build());

        assertEquals(1, unbound.size());
        assertEquals("git_diff", unbound.get(0));
    }

    @Test
    void 无提示词模板时产出空串而不是崩() {
        SystemPromptSnapshot snapshot = new SystemPromptAssembler()
                .assemble(AgentSnapshotInput.builder(AgentId.of("a")).build());

        assertEquals("", snapshot.getRenderedPrompt());
        assertTrue(snapshot.getToolList().isEmpty());
    }
}
