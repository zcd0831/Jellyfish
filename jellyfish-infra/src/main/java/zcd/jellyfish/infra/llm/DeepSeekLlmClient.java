package zcd.jellyfish.infra.llm;

import okhttp3.OkHttpClient;
import zcd.jellyfish.infra.config.Provider;

import java.util.concurrent.ExecutorService;

/**
 * DeepSeek 实现。DeepSeek 提供 OpenAI 兼容接口，复用 OpenAI 协议基类。
 *
 * @author zcd
 */
public class DeepSeekLlmClient extends AbstractOpenAiCompatibleLlmClient {

    /** DeepSeek 官方默认 baseUrl。 */
    static final String DEFAULT_BASE_URL = "https://api.deepseek.com";

    /**
     * 构造 DeepSeek 客户端。
     *
     * @param provider       provider 配置
     * @param httpClient     共享的 HTTP 客户端
     * @param streamExecutor 流式请求线程池
     */
    public DeepSeekLlmClient(Provider provider, OkHttpClient httpClient, ExecutorService streamExecutor) {
        super(provider, httpClient, streamExecutor);
    }

    /**
     * 获取 DeepSeek 默认 baseUrl。
     *
     * @return 默认 baseUrl
     */
    @Override
    protected String defaultBaseUrl() {
        return DEFAULT_BASE_URL;
    }
}
