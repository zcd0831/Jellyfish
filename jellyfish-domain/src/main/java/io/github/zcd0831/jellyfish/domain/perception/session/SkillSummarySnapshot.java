package io.github.zcd0831.jellyfish.domain.perception.session;

import lombok.Value;

@Value
public class SkillSummarySnapshot {

    String name;
    String description;
    /** 渐进披露的浅层三件套之一；SOP 正文不冻结（04-perception §4.4）。 */
    String triggerCondition;
}
