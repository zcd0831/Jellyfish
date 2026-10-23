package io.github.zcd0831.jellyfish.domain.perception.session;

import lombok.Value;

@Value
public class ContextWindowPolicy {

    int recordCount;
    double systemRatioLimit;
}
