package zcd.jellyfish.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.config.Provider;
import zcd.jellyfish.infra.support.LlmClients;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * OpenAI 官方实现（Chat Completions 协议），额外支持 embeddings 接口。
 *
 * @author zcd
 */
public class OpenAiLlmClient extends AbstractOpenAiCompatibleLlmClient {

    /** OpenAI 官方默认 baseUrl。 */
    static final String DEFAULT_BASE_URL = "https://api.openai.com";

    /** 未指定向量化模型时使用的默认模型。 */
    private static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-3-large";

    /**
     * 构造 OpenAI 客户端。
     *
     * @param provider       provider 配置
     * @param httpClient     共享的 HTTP 客户端
     * @param streamExecutor 流式请求线程池
     */
    public OpenAiLlmClient(Provider provider, OkHttpClient httpClient, ExecutorService streamExecutor) {
        super(provider, httpClient, streamExecutor);
    }

    /**
     * 获取 OpenAI 默认 baseUrl。
     *
     * @return 默认 baseUrl
     */
    @Override
    protected String defaultBaseUrl() {
        return DEFAULT_BASE_URL;
    }

    /**
     * 调用 OpenAI embeddings 接口做文本向量化。
     *
     * @param input 待向量化的文本
     * @return 向量
     * @throws JellyfishException 响应为空、结构非法或请求失败时抛出
     */
    @Override
    public double[] embedding(String input) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", DEFAULT_EMBEDDING_MODEL);
        body.put("input", input);
        Request request = authorizedRequest(LlmClients.appendVersion(baseUrl(), "v1", "/embeddings"))
                .post(jsonBody(body))
                .build();
        JsonNode root = executeForJson(request, JsonNode.class, "embedding request");
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new JellyfishException("embedding response is empty for provider: " + provider.getName());
        }
        JsonNode values = data.get(0).path("embedding");
        if (!values.isArray()) {
            throw new JellyfishException("embedding response is invalid for provider: " + provider.getName());
        }
        double[] result = new double[values.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = values.get(i).asDouble();
        }
        return result;
    }
}
