package io.github.zcd0831.jellyfish.application.memory;

import io.github.zcd0831.jellyfish.domain.shared.SkillId;

/**
 * 子代理说的技能（不可信输入）：{@code CREATE} 新建、{@code MERGE} 改已有可变技能。
 * 规则出处：模块-记忆 §5、§8.5。
 */
public final class SkillDraft {

    public enum Action {
        CREATE,
        MERGE
    }

    private final Action action;
    private final SkillId targetSkillId;
    private final String name;
    private final String description;
    private final String triggerCondition;
    private final String sopBody;

    public SkillDraft(Action action, SkillId targetSkillId, String name, String description,
                      String triggerCondition, String sopBody) {
        this.action = action;
        this.targetSkillId = targetSkillId;
        this.name = name;
        this.description = description;
        this.triggerCondition = triggerCondition;
        this.sopBody = sopBody;
    }

    public boolean isMerge() {
        return action == Action.MERGE;
    }

    /**
     * 四件套缺一即沉淀失败：描述写坏了技能就不可发现（§7.3 不变量 ④）。
     * MERGE 也必须带全——目标不可变时要能降级成 CREATE（§8.5），缺三件套就降不了。
     */
    public boolean isComplete() {
        return notBlank(name) && notBlank(description) && notBlank(triggerCondition) && notBlank(sopBody);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public Action action() {
        return action;
    }

    public SkillId targetSkillId() {
        return targetSkillId;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public String triggerCondition() {
        return triggerCondition;
    }

    public String sopBody() {
        return sopBody;
    }
}
