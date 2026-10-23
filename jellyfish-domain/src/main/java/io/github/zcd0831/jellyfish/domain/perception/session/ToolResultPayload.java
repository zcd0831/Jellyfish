package io.github.zcd0831.jellyfish.domain.perception.session;

import lombok.Value;

/** 工具结果的附属信息。规则出处：模块-感知 §4.6。 */
@Value
public class ToolResultPayload {

    boolean isTruncated;
    String resourceUri;
}
