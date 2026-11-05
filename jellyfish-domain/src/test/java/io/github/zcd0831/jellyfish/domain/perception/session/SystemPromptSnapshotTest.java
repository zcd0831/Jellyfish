package io.github.zcd0831.jellyfish.domain.perception.session;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 冻结就是冻结：拿到快照的人改不动里面的列表，改不动构造时传进来的那个列表。
 * 规则出处：04-perception §4.8.1 规则 4（会话开启时冻结）、§4.9.5（值对象不可变）。
 */
class SystemPromptSnapshotTest {

    private static ToolSchemaSnapshot tool(String id) {
        return new ToolSchemaSnapshot(id, "说明" + id, "{}", "");
    }

    @Test
    void 快照里的工具列表不可被就地修改() {
        List<ToolSchemaSnapshot> tools = new ArrayList<ToolSchemaSnapshot>();
        tools.add(tool("read_file"));
        SystemPromptSnapshot snapshot = new SystemPromptSnapshot("系统提示词", tools,
                Collections.<SkillSummarySnapshot>emptyList());

        assertThrows(UnsupportedOperationException.class, () -> snapshot.getToolList().add(tool("run_command")));
        assertEquals(1, snapshot.getToolList().size(), "工具条数不能被改掉");
    }

    @Test
    void 构造后改原列表也影响不到快照() {
        List<ToolSchemaSnapshot> tools = new ArrayList<ToolSchemaSnapshot>();
        tools.add(tool("read_file"));
        SystemPromptSnapshot snapshot = new SystemPromptSnapshot("系统提示词", tools,
                Collections.<SkillSummarySnapshot>emptyList());

        tools.add(tool("run_command"));

        assertEquals(1, snapshot.getToolList().size(), "快照必须拷贝，不能共享同一个列表");
    }
}
