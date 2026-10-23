package io.github.zcd0831.jellyfish.domain.perception.session;

import lombok.Value;

@Value
public class ToolSchemaSnapshot {

    String name;
    String description;
    String parametersJsonSchema;
    String outputDescription;
}
