package io.github.zcd0831.jellyfish.domain.memory.skill;

import java.util.List;

import lombok.Value;

@Value
public class InstructionTemplate {

    String rawMarkdown;
    List<String> variables;
}
