package io.github.zcd0831.jellyfish.domain.memory.skill;

import lombok.Value;

/**
 * SOP 原文。系统不解析、不渲染、不做变量替换。
 */
@Value
public class InstructionTemplate {

    String rawMarkdown;
}
