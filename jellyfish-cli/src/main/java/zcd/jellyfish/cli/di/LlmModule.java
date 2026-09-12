package zcd.jellyfish.cli.di;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import dagger.multibindings.StringKey;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import zcd.jellyfish.infra.llm.ClaudeLlmClient;
import zcd.jellyfish.infra.llm.DeepSeekLlmClient;
import zcd.jellyfish.infra.llm.GeminiLlmClient;
import zcd.jellyfish.infra.llm.LlmClientCreator;
import zcd.jellyfish.infra.llm.MiniMaxLlmClient;
import zcd.jellyfish.infra.llm.OpenAiLlmClient;
import zcd.jellyfish.infra.support.ProviderTypes;

import javax.inject.Singleton;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * LLM 相关依赖的 Dagger2 模块。
 * <ul>
 *     <li>提供全局唯一的 {@link OkHttpClient}，所有 LlmClient 共享其连接池；</li>
 *     <li>提供流式调用使用的守护线程池；</li>
 *     <li>通过 {@code @IntoMap} 将所有 LlmClient 实现注册到
 *     {@code Map<String, LlmClientCreator>}，再由 {@code LlmClientFactory} 消费。</li>
 * </ul>
 * 组件与 Module 只存在于最外层 {@code jellyfish-cli}，infra 只暴露带 {@code @Inject} 的构造器。
 *
 * @author zcd
 */
@Module
public final class LlmModule {

    private static final int MAX_IDLE_CONNECTIONS = 10;

    private static final long KEEP_ALIVE_MINUTES = 5L;

    /** 流式线程池的线程数上限，即并发流式请求上限。 */
    private static final int STREAM_MAX_THREADS = 32;

    /** 流式线程池等待队列容量，队列满后拒绝任务并让调用方拿到异常。 */
    private static final int STREAM_QUEUE_CAPACITY = 128;

    /** 流式线程空闲回收时间（秒）。 */
    private static final long STREAM_KEEP_ALIVE_SECONDS = 60L;

    private LlmModule() {
    }

    /**
     * 提供全局共享的 HTTP 客户端。
     *
     * @return OkHttpClient
     */
    @Provides
    @Singleton
    static OkHttpClient provideOkHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .connectionPool(new ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
                .build();
    }

    /**
     * 流式请求在后台线程执行，避免阻塞调用方。
     * <p>
     * 流式调用是长连接、突发型负载，使用无界缓存线程池会无限创建线程，这里改为有界线程池：
     * 最多 {@value #STREAM_MAX_THREADS} 个并发流，超出后进入容量 {@value #STREAM_QUEUE_CAPACITY} 的等待队列，
     * 队列也满时拒绝任务（由 {@code AbstractHttpLlmClient#startStream} 包装为业务异常）。
     * 核心与最大线程数相等且允许空闲回收，空闲时线程会自动消亡，不阻止 JVM 退出。
     *
     * @return 流式调用线程池
     */
    @Provides
    @Singleton
    static ExecutorService provideStreamExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                STREAM_MAX_THREADS,
                STREAM_MAX_THREADS,
                STREAM_KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(STREAM_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "llm-stream");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * 注册 OpenAI 客户端创建器。
     *
     * @param httpClient     HTTP 客户端
     * @param streamExecutor 流式线程池
     * @return 客户端创建器
     */
    @Provides
    @IntoMap
    @StringKey(ProviderTypes.OPENAI)
    static LlmClientCreator openAiClientCreator(OkHttpClient httpClient, ExecutorService streamExecutor) {
        return provider -> new OpenAiLlmClient(provider, httpClient, streamExecutor);
    }

    /**
     * 注册 DeepSeek 客户端创建器。
     *
     * @param httpClient     HTTP 客户端
     * @param streamExecutor 流式线程池
     * @return 客户端创建器
     */
    @Provides
    @IntoMap
    @StringKey(ProviderTypes.DEEPSEEK)
    static LlmClientCreator deepSeekClientCreator(OkHttpClient httpClient, ExecutorService streamExecutor) {
        return provider -> new DeepSeekLlmClient(provider, httpClient, streamExecutor);
    }

    /**
     * 注册 MiniMax 客户端创建器。
     *
     * @param httpClient     HTTP 客户端
     * @param streamExecutor 流式线程池
     * @return 客户端创建器
     */
    @Provides
    @IntoMap
    @StringKey(ProviderTypes.MINIMAX)
    static LlmClientCreator miniMaxClientCreator(OkHttpClient httpClient, ExecutorService streamExecutor) {
        return provider -> new MiniMaxLlmClient(provider, httpClient, streamExecutor);
    }

    /**
     * 注册 Gemini 客户端创建器。
     *
     * @param httpClient     HTTP 客户端
     * @param streamExecutor 流式线程池
     * @return 客户端创建器
     */
    @Provides
    @IntoMap
    @StringKey(ProviderTypes.GEMINI)
    static LlmClientCreator geminiClientCreator(OkHttpClient httpClient, ExecutorService streamExecutor) {
        return provider -> new GeminiLlmClient(provider, httpClient, streamExecutor);
    }

    /**
     * 注册 Gemini（google 别名）客户端创建器。
     *
     * @param httpClient     HTTP 客户端
     * @param streamExecutor 流式线程池
     * @return 客户端创建器
     */
    @Provides
    @IntoMap
    @StringKey(ProviderTypes.GOOGLE)
    static LlmClientCreator googleClientCreator(OkHttpClient httpClient, ExecutorService streamExecutor) {
        return provider -> new GeminiLlmClient(provider, httpClient, streamExecutor);
    }

    /**
     * 注册 Claude 客户端创建器。
     *
     * @param httpClient     HTTP 客户端
     * @param streamExecutor 流式线程池
     * @return 客户端创建器
     */
    @Provides
    @IntoMap
    @StringKey(ProviderTypes.CLAUDE)
    static LlmClientCreator claudeClientCreator(OkHttpClient httpClient, ExecutorService streamExecutor) {
        return provider -> new ClaudeLlmClient(provider, httpClient, streamExecutor);
    }

    /**
     * 注册 Claude（anthropic 别名）客户端创建器。
     *
     * @param httpClient     HTTP 客户端
     * @param streamExecutor 流式线程池
     * @return 客户端创建器
     */
    @Provides
    @IntoMap
    @StringKey(ProviderTypes.ANTHROPIC)
    static LlmClientCreator anthropicClientCreator(OkHttpClient httpClient, ExecutorService streamExecutor) {
        return provider -> new ClaudeLlmClient(provider, httpClient, streamExecutor);
    }
}
