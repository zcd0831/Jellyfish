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

    public Model(ModelId id, ProviderId providerId, String modelName, ModelStatus status,
                 ContextLimit contextLimit, List<ModelCapability> capabilities) {
        this.id = id;
        this.providerId = providerId;
        this.modelName = modelName;
        this.status = status;
        this.contextLimit = contextLimit;
        this.capabilities = Collections.unmodifiableList(new ArrayList<ModelCapability>(
                capabilities == null ? Collections.<ModelCapability>emptyList() : capabilities));
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
