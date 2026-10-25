package io.github.zcd0831.jellyfish.infrastructure.model;

import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;

import java.util.Map;

/**
 * 线协议接缝：应用层只产出厂商中立的 canonical 消息，形态差异全部由实现吸收
 * （一个响应一条 assistant 消息、续接同轮 tool_result、reasoning 私有状态等）。
 * 规则出处：模块-行动 §8.2 第 7/8 条、模块-基础设施层 §10 第 7 条。
 */
public interface ChatProtocolAdapter {

    /** 组装请求体；`maxOutputTokens` 给需要显式输出上限的协议用。 */
    String buildRequest(ModelInvocationRequest request, String modelName, int maxOutputTokens, boolean stream);

    /** 解析非流式响应。 */
    ModelInvocationResult parseResponse(String body, ModelId servedModelId);

    /** 每次调用一个新的流式累加器（有状态，不能复用）。 */
    StreamAccumulator newStreamAccumulator();

    /** 请求 URL（端点拼接、query 里的凭据等都归协议）。 */
    String url(String baseEndpoint, String modelName, String apiKey, boolean stream);

    /** 请求头（鉴权方式归协议）。 */
    Map<String, String> headers(String apiKey);

    interface StreamAccumulator {

        /** 返回 false 表示这是最后一条（`[DONE]`）。 */
        boolean accept(String data);

        /** 当前 chunk 的文本增量；仅供外壳打字机效果。 */
        String lastDelta();

        ModelInvocationResult result(ModelId servedModelId);
    }
}
