package zcd.jellyfish.infra.llm;

import okhttp3.OkHttpClient;
import zcd.jellyfish.infra.config.Provider;

import java.util.concurrent.ExecutorService;

/**
 * MiniMax 实现。MiniMax 提供 OpenAI 兼容接口，复用 OpenAI 协议基类。
 * <p>
 * 国际站默认 {@code https://api.minimax.io}；中国大陆站可通过 provider.baseUrl 覆盖为
 * {@code https://api.minimaxi.com}。
 *
 * @author zcd
 */
public class MiniMaxLlmClient extends AbstractOpenAiCompatibleLlmClient {

    /** MiniMax 国际站默认 baseUrl。 */
    static final String DEFAULT_BASE_URL = "https://api.minimax.io";

    /**
     * 构造 MiniMax 客户端。
     *
     * @param provider       provider 配置
     * @param httpClient     共享的 HTTP 客户端
     * @param streamExecutor 流式请求线程池
     */
    public MiniMaxLlmClient(Provider provider, OkHttpClient httpClient, ExecutorService streamExecutor) {
        super(provider, httpClient, streamExecutor);
    }

    /**
     * 获取 MiniMax 默认 baseUrl。
     *
     * @return 默认 baseUrl
     */
    @Override
    protected String defaultBaseUrl() {
        return DEFAULT_BASE_URL;
    }
}
