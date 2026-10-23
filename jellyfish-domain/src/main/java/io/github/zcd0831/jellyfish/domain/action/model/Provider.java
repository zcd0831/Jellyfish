package io.github.zcd0831.jellyfish.domain.action.model;

import io.github.zcd0831.jellyfish.domain.shared.ProviderId;
/** 规则出处：模块-行动 §7.2。 */
public class Provider {

    private ProviderId id;
    private String name;
    private String apiEndpoint;
    private ApiCredential credential;
    private ProviderStatus status;
}
