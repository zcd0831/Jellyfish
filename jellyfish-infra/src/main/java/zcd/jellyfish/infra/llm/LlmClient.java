package zcd.jellyfish.infra.llm;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.config.Provider;

import java.util.Collections;
import java.util.List;

/**
 * 统一的 LLM 调用接口。所有厂商实现都通过 {@link LlmClientFactory} 按
 * {@link Provider#getType()} 动态获取。
 *
 * @author zcd
 */
public interface LlmClient {

    /**
     * 获取当前客户端绑定的 provider。
     *
     * @return provider，包含实际使用的 apiKey / baseUrl
     */
    Provider getProvider();

    /**
     * 同步（非流式）对话调用。
     *
     * @param request 统一请求模型
     * @return 统一返回结果
     */
    LlmResponse chat(LlmRequest request);

    /**
     * 流式对话调用，事件通过监听器回调。
     *
     * @param request  统一请求模型
     * @param listener 流式响应监听器
     * @return 可用于取消本次请求的句柄
     */
    LlmStreamHandle chatStream(LlmRequest request, LlmStreamListener listener);

    /**
     * 拉取可用模型列表。默认返回空列表，表示该实现不支持查询。
     *
     * @return 模型名列表，可能为空但不会为 {@code null}
     */
    default List<String> listModels() {
        return Collections.emptyList();
    }

    /**
     * 文本向量化。默认不支持。
     *
     * @param input 待向量化的文本
     * @return 向量
     * @throws JellyfishException 当前实现不支持向量化时抛出
     */
    default double[] embedding(String input) {
        throw new JellyfishException("embedding is not supported by " + getClass().getSimpleName());
    }
}
