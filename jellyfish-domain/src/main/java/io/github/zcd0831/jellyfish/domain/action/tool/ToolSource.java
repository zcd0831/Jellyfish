package io.github.zcd0831.jellyfish.domain.action.tool;

import lombok.Value;

@Value
public class ToolSource {

    SourceType type;
    String endpointOrIdentifier;
}
