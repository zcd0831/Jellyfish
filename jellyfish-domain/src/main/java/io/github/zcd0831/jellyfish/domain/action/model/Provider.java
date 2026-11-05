package io.github.zcd0831.jellyfish.domain.action.model;

import io.github.zcd0831.jellyfish.domain.shared.ProviderId;

/** 规则出处：05-action §5.4.2、§5.8.2。 */
public class Provider {

    private final ProviderId id;
    private final String name;
    private final String apiEndpoint;
    private final ChatProtocol protocol;

    private ApiCredential credential;
    private ProviderStatus status;

    private Provider(Builder builder) {
        this.id = builder.id;
        this.name = builder.name;
        this.apiEndpoint = builder.apiEndpoint;
        this.protocol = builder.protocol == null ? ChatProtocol.OPENAI_CHAT : builder.protocol;
        this.credential = builder.credential;
        this.status = builder.status;
    }

    /** 身份与端点必填；协议缺省 OPENAI_CHAT（05-action §5.4.2）。 */
    public static Builder builder(ProviderId id, String name, String apiEndpoint) {
        return new Builder(id, name, apiEndpoint);
    }

    public static final class Builder {

        private final ProviderId id;
        private final String name;
        private final String apiEndpoint;
        private ChatProtocol protocol = ChatProtocol.OPENAI_CHAT;
        private ApiCredential credential;
        private ProviderStatus status = ProviderStatus.ACTIVE;

        private Builder(ProviderId id, String name, String apiEndpoint) {
            if (id == null) {
                throw new IllegalArgumentException("Provider id 不能为空");
            }
            this.id = id;
            this.name = name;
            this.apiEndpoint = apiEndpoint;
        }

        public Builder protocol(ChatProtocol value) {
            this.protocol = value;
            return this;
        }

        public Builder credential(ApiCredential value) {
            this.credential = value;
            return this;
        }

        public Builder status(ProviderStatus value) {
            this.status = value;
            return this;
        }

        public Provider build() {
            return new Provider(this);
        }
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

    /** 规则出处：05-action §5.8.2 规则 3（弃用的 Provider 不得选中）。 */
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
