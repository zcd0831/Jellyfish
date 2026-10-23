package io.github.zcd0831.jellyfish.domain.action.model;

import java.util.List;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.ProviderId;

/** 规则出处：模块-行动 §7.3。 */
public class Model {

    private ModelId id;
    private ProviderId providerId;
    private String modelName;
    private ModelStatus status;
    private ContextLimit contextLimit;
    private List<ModelCapability> capabilities;
}
