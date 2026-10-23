package io.github.zcd0831.jellyfish.domain.action.model;

import lombok.Value;

/** 两个上限相互独立。规则出处：模块-行动 §5.6。 */
@Value
public class ContextLimit {

    int maxInputTokens;
    int maxOutputTokens;
}
