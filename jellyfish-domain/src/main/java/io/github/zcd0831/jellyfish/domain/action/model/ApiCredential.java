package io.github.zcd0831.jellyfish.domain.action.model;

import lombok.Value;

/** 凭据引用 = 环境变量名。。 */
@Value
public class ApiCredential {

    String apiKeyRef;
}
