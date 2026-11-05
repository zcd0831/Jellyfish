package io.github.zcd0831.jellyfish.domain.perception.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话开启时冻结的内容快照：模型看到的工具、技能与系统提示词，整轮会话不再变。
 * <p>
 * 两个列表在构造器里做防御拷贝：{@code final} 只锁引用，拿到快照的人一句
 * {@code getToolList().add(...)} 就能改掉"已冻结"的内容，改动会被下一轮请求原样发出去。
 * 规则出处：04-perception §4.8.1 规则 4、§4.9.5（值对象不可变）。
 */
public final class SystemPromptSnapshot {

    private final String renderedPrompt;
    private final List<ToolSchemaSnapshot> toolList;
    private final List<SkillSummarySnapshot> skillList;

    public SystemPromptSnapshot(String renderedPrompt, List<ToolSchemaSnapshot> toolList,
                                List<SkillSummarySnapshot> skillList) {
        this.renderedPrompt = renderedPrompt;
        this.toolList = Collections.unmodifiableList(new ArrayList<ToolSchemaSnapshot>(
                toolList == null ? Collections.<ToolSchemaSnapshot>emptyList() : toolList));
        this.skillList = Collections.unmodifiableList(new ArrayList<SkillSummarySnapshot>(
                skillList == null ? Collections.<SkillSummarySnapshot>emptyList() : skillList));
    }

    public String getRenderedPrompt() {
        return renderedPrompt;
    }

    public List<ToolSchemaSnapshot> getToolList() {
        return toolList;
    }

    public List<SkillSummarySnapshot> getSkillList() {
        return skillList;
    }
}
