package io.github.zcd0831.jellyfish.domain.action.model;

import io.github.zcd0831.jellyfish.domain.shared.ProviderId;

/** 规则出处：模块-行动 §3.2、§7.2。 */
public class Provider {

    private final ProviderId id;
    private final String name;
    private final String apiEndpoint;
    private final ChatProtocol protocol;

    private ApiCredential credential;
    private ProviderStatus status;

    public Provider(ProviderId id, String name, String apiEndpoint, ChatProtocol protocol,
                    ApiCredential credential, ProviderStatus status) {
        this.id = id;
        this.name = name;
        this.apiEndpoint = apiEndpoint;
        this.protocol = protocol == null ? ChatProtocol.OPENAI_CHAT : protocol;
        this.credential = credential;
        this.status = status;
    }

    public void updateCredential(ApiCredential credential) {
        this.credential = credential;
    }

    public void activate() {
        this.status = ProviderStatus.ACTIVE;
    }

    public void deprecate() {
        this.status = ProviderStatus.DEPRECATED;
    }

    /** 规则出处：模块-行动 §7.2 规则 ③（弃用的 Provider 不得选中）。 */
    public boolean isActive() {
        return status == ProviderStatus.ACTIVE;
    }

    public ProviderId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String apiEndpoint() {
        return apiEndpoint;
    }

    public ChatProtocol protocol() {
        return protocol;
    }

    public ApiCredential credential() {
        return credential;
    }

    public ProviderStatus status() {
        return status;
    }
}
