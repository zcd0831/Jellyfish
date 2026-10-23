package io.github.zcd0831.jellyfish.domain.action.agent;

import java.util.List;

import lombok.Value;

@Value
public class PromptTemplate {

    String templateText;
    List<String> variables;
}
