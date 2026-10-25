package io.github.zcd0831.jellyfish.domain.memory.skill;

import lombok.Value;

/**
 * SOP 原文。系统不解析、不渲染、不做变量替换（模块-记忆 §7.3 规则 ①，D23-5）。
 */
@Value
public class InstructionTemplate {

    String rawMarkdown;
}
