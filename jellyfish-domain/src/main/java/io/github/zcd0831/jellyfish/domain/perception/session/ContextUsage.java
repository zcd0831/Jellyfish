package io.github.zcd0831.jellyfish.domain.perception.session;

import lombok.Value;

@Value
public class ContextUsage {

    int usedTokens;
    int inputCeiling;
    int systemTokens;
    int summaryTokens;
    int windowRecordCount;
}
