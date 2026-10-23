package io.github.zcd0831.jellyfish.application.action.port;

import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.action.port.ModelProfile;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;

/**
 * 模型调用的唯一出口。规则出处：模块-应用层 §6、技术决策 D06。
 */
public interface ModelInvocationPort {

    ModelInvocationResult invoke(ModelInvocationRequest request);

    /** 流式：增量交给 sink（只喂界面），返回值仍是完整文本与用量。 */
    ModelInvocationResult invokeStreaming(ModelInvocationRequest request, ChunkSink sink);

    ModelProfile describe(ModelId modelId);
}
