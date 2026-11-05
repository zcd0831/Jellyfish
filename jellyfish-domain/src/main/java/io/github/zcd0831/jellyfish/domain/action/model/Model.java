package io.github.zcd0831.jellyfish.domain.action.model;

import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.ProviderId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 规则出处：05-action §5.4.2、§5.8.3。 */
public class Model {

    private final ModelId id;
    private final ProviderId providerId;
    private final String modelName;
    private final ContextLimit contextLimit;
    private final List<ModelCapability> capabilities;

    private ModelStatus status;

    private Model(Builder builder) {
        this.id = builder.id;
        this.providerId = builder.providerId;
        this.modelName = builder.modelName;
        this.status = builder.status;
        this.contextLimit = builder.contextLimit;
        this.capabilities = Collections.unmodifiableList(new ArrayList<ModelCapability>(
                builder.capabilities == null ? Collections.<ModelCapability>emptyList() : builder.capabilities));
    }

    /** 身份三项必填；能力标签与上下文上限按需设置（05-action §5.4.2）。 */
    public static Builder builder(ModelId id, ProviderId providerId, String modelName) {
        return new Builder(id, providerId, modelName);
    }

    public static final class Builder {

        private final ModelId id;
        private final ProviderId providerId;
        private final String modelName;
        private ModelStatus status = ModelStatus.ACTIVE;
        private ContextLimit contextLimit;
        private List<ModelCapability> capabilities;

        private Builder(ModelId id, ProviderId providerId, String modelName) {
            if (id == null || providerId == null) {
                throw new IllegalArgumentException("模型 id 与所属 Provider 不能为空");
            }
            this.id = id;
            this.providerId = providerId;
            this.modelName = modelName;
        }

        public Builder status(ModelStatus value) {
            this.status = value;
            return this;
        }

        public Builder contextLimit(ContextLimit value) {
            this.contextLimit = value;
            return this;
        }

        public Builder capabilities(List<ModelCapability> value) {
            this.capabilities = value;
            return this;
        }

        public Model build() {
            return new Model(this);
        }
    }

    /** 规则出处：05-action §5.8.3 规则 1（只有 ACTIVE 可调用）。 */
    public boolean isCallable() {
        return status == ModelStatus.ACTIVE;
    }

    /** 规则出处：05-action §5.4.2（能力标签的唯一消费者是选模型的 LLM）。 */
    public boolean hasCapability(ModelCapability capability) {
        return capabilities.contains(capability);
    }

    public void activate() {
        this.status = ModelStatus.ACTIVE;
    }

    public void deprecate() {
        this.status = ModelStatus.DEPRECATED;
    }

    public ModelId id() {
        return id;
    }

    public ProviderId providerId() {
        return providerId;
    }

    public String modelName() {
        return modelName;
    }

    public ModelStatus status() {
        return status;
    }

    public ContextLimit contextLimit() {
        return contextLimit;
    }

    public List<ModelCapability> capabilities() {
        return capabilities;
    }
}
