package io.github.zcd0831.jellyfish.infrastructure.model;

import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationException;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.action.port.ModelProfile;
import io.github.zcd0831.jellyfish.application.action.port.ModelUsage;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.shared.CancellationToken;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;
import io.github.zcd0831.jellyfish.application.shared.port.CancellationRegistry;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.application.shared.port.TokenCounter;
import io.github.zcd0831.jellyfish.domain.action.model.ApiCredential;
import io.github.zcd0831.jellyfish.domain.action.model.ChatProtocol;
import io.github.zcd0831.jellyfish.domain.action.model.Model;
import io.github.zcd0831.jellyfish.domain.action.model.ModelError;
import io.github.zcd0831.jellyfish.domain.action.model.Provider;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.infrastructure.log.ModelCallLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 模型调用的唯一出口：选模型 → 硬校验 → 调用 → 同模型重试 → 备用链降级。
 * 只返回 usage，不记账（05-action §5.9.2、09-infrastructure §9.11）。
 */
public final class ModelInvoker implements ModelInvocationPort {

    private static final int[] BACKOFF_MILLIS = {500, 1500, 4000};
    private static final long MAX_RETRY_AFTER_SECONDS = 60L;

    private final CatalogRepository catalog;
    private final PolicyRepository policyRepository;
    private final ChatTransport transport;
    private final ProgressPort progress;
    private final TokenCounter tokenCounter;
    /** 协议适配器按 `Provider.protocol` 选择；本期只实现 OPENAI_CHAT。 */
    private final ChatCompletionsProtocol openAiProtocol = new ChatCompletionsProtocol();
    private final AnthropicMessagesProtocol anthropicProtocol = new AnthropicMessagesProtocol();
    private final GeminiProtocol geminiProtocol = new GeminiProtocol();
    private final Random jitter = new Random();
    private final Sleeper sleeper;
    private final CredentialLookup credentials;
    private final CancellationRegistry cancellations;

    /** 没有登记表的调用（单测、插件自建实例）按「永不终止」处理。 */
    private static final CancellationRegistry NO_CANCELLATIONS = new CancellationRegistry() {
        @Override
        public CancellationToken forSession(io.github.zcd0831.jellyfish.domain.shared.SessionId sessionId) {
            return CancellationToken.never();
        }
    };

    public ModelInvoker(CatalogRepository catalog, PolicyRepository policyRepository, ChatTransport transport,
                        ProgressPort progress, TokenCounter tokenCounter, CancellationRegistry cancellations) {
        this(catalog, policyRepository, transport, progress, tokenCounter,
                new Sleeper() {
                    @Override
                    public void sleep(long millis) throws InterruptedException {
                        Thread.sleep(millis);
                    }
                },
                new CredentialLookup() {
                    @Override
                    public String lookup(String envName) {
                        return System.getenv(envName);
                    }
                },
                cancellations);
    }

    /** 没有会话归属的实例（测试、插件自建）：终止信号一律「永不取消」。 */
    public ModelInvoker(CatalogRepository catalog, PolicyRepository policyRepository, ChatTransport transport,
                        ProgressPort progress, TokenCounter tokenCounter) {
        this(catalog, policyRepository, transport, progress, tokenCounter, NO_CANCELLATIONS);
    }

    ModelInvoker(CatalogRepository catalog, PolicyRepository policyRepository, ChatTransport transport,
                 ProgressPort progress, TokenCounter tokenCounter, Sleeper sleeper,
                 CredentialLookup credentials) {
        this(catalog, policyRepository, transport, progress, tokenCounter, sleeper, credentials, NO_CANCELLATIONS);
    }

    ModelInvoker(CatalogRepository catalog, PolicyRepository policyRepository, ChatTransport transport,
                 ProgressPort progress, TokenCounter tokenCounter, Sleeper sleeper,
                 CredentialLookup credentials, CancellationRegistry cancellations) {
        this.catalog = catalog;
        this.policyRepository = policyRepository;
        this.transport = transport;
        this.progress = progress;
        this.tokenCounter = tokenCounter;
        this.sleeper = sleeper;
        this.credentials = credentials;
        this.cancellations = cancellations;
    }

    @Override
    public ModelInvocationResult invoke(ModelInvocationRequest request) {
        return invokeChain(request, null);
    }

    @Override
    public ModelInvocationResult invokeStreaming(ModelInvocationRequest request, ChunkSink sink) {
        return invokeChain(request, sink);
    }

    @Override
    public ModelProfile describe(ModelId modelId) {
        Model model = findModel(modelId);
        if (model == null) {
            throw new ModelInvocationException(ModelError.MODEL_NOT_FOUND, "模型不存在：" + modelId.getValue());
        }
        return new ModelProfile(model.contextLimit().getMaxInputTokens(), model.contextLimit().getMaxOutputTokens());
    }

    /**
     * 目标模型失败后按 `modelFallbackChain` 逐个降级，每个模型重新做硬校验。
     * 「请求超限」不降级：交应用层折叠后重试（08-application §8.10）。
     */
    private ModelInvocationResult invokeChain(ModelInvocationRequest request, ChunkSink sink) {
        List<ModelId> chain = new ArrayList<>();
        chain.add(request.targetModelId());
        for (ModelId fallback : policyRepository.load().modelFallbackChain()) {
            if (!chain.contains(fallback)) {
                chain.add(fallback);
            }
        }
        // 终止信号按会话取（不是线程中断、不是共享槽位）：08-application §8.9.4.1
        CancellationToken token = request.sessionId() == null
                ? CancellationToken.never() : cancellations.forSession(request.sessionId());
        ModelInvocationException lastFailure = null;
        boolean first = true;
        for (ModelId modelId : chain) {
            token.throwIfCancelled();
            if (!first) {
                ModelCallLog.switched(chain.get(0).getValue(), modelId.getValue());
            }
            first = false;
            Model model = findModel(modelId);
            if (model == null || !model.isCallable()) {
                continue;
            }
            Provider provider = findProvider(model);
            if (provider == null || !provider.isActive()) {
                continue;
            }
            ChatProtocolAdapter adapter = adapterFor(provider);
            if (adapter == null) {
                ModelCallLog.unavailable(modelId.getValue(), ModelError.UNAVAILABLE.code());
                lastFailure = new ModelInvocationException(ModelError.UNAVAILABLE,
                        "协议未实现：" + provider.protocol() + "（" + modelId.getValue() + "）");
                continue;
            }
            hardCheck(request.estimatedPromptTokens(), model);
            try {
                return callWithRetry(request, model, provider, adapter, sink, token);
            } catch (ModelInvocationException e) {
                if (e.error() == ModelError.CONTEXT_EXCEEDED) {
                    throw e;
                }
                ModelCallLog.unavailable(modelId.getValue(), e.error().code());
                lastFailure = e;
            }
        }
        throw lastFailure != null ? lastFailure
                : new ModelInvocationException(ModelError.UNAVAILABLE, "没有可调用的模型：" + request.targetModelId().getValue());
    }

    /** 调用侧硬校验：输入与输出是两个独立上限，这里只看输入（05-action §5.8.3 规则 2）。 */
    private void hardCheck(int estimatedPromptTokens, Model model) {
        int maxInput = model.contextLimit().getMaxInputTokens();
        if (estimatedPromptTokens > maxInput) {
            throw new ModelInvocationException(ModelError.CONTEXT_EXCEEDED,
                    "请求超限：估算 " + estimatedPromptTokens + " > 上限 " + maxInput + "（" + model.id().getValue() + "）");
        }
    }

    /** 协议 → 适配器；未实现的协议返回 null，由调用方按"不可用"降级。 */
    private ChatProtocolAdapter adapterFor(Provider provider) {
        if (provider.protocol() == ChatProtocol.OPENAI_CHAT) {
            return openAiProtocol;
        }
        if (provider.protocol() == ChatProtocol.ANTHROPIC_MESSAGES) {
            return anthropicProtocol;
        }
        if (provider.protocol() == ChatProtocol.GEMINI) {
            return geminiProtocol;
        }
        return null;
    }

    private ModelInvocationResult callWithRetry(ModelInvocationRequest request, Model model, Provider provider,
                                                ChatProtocolAdapter adapter, ChunkSink sink, CancellationToken token) {
        int attempt = 0;
        while (true) {
            token.throwIfCancelled();
            try {
                return callOnce(request, model, provider, adapter, sink, token);
            } catch (RetryableFailure e) {
                if (token.isCancelled()) {
                    throw new java.util.concurrent.CancellationException("本轮已被终止：不再重试、不降级");
                }
                if (attempt >= BACKOFF_MILLIS.length) {
                    throw new ModelInvocationException(ModelError.UNAVAILABLE,
                            "重试用尽（" + model.id().getValue() + "）：" + e.getMessage(), e);
                }
                long delay = e.retryAfterMillis() != null ? e.retryAfterMillis() : backoff(attempt);
                if (e.retryAfterMillis() != null && e.retryAfterMillis() > MAX_RETRY_AFTER_SECONDS * 1000) {
                    throw new ModelInvocationException(ModelError.UNAVAILABLE,
                            "Retry-After 超过 60s，直接降级（" + model.id().getValue() + "）", e);
                }
                ModelCallLog.retry(model.id().getValue(), attempt + 1, delay);
                progress.emit(ProgressEvent.of(ProgressEvent.Kind.RETRY_SCHEDULED,
                        "第 " + (attempt + 1) + " 次重试，" + delay + "ms 后（" + e.getMessage() + "）"));
                sleep(delay);
                attempt++;
            }
        }
    }

    private ModelInvocationResult callOnce(ModelInvocationRequest request, Model model, Provider provider,
                                           ChatProtocolAdapter adapter, ChunkSink sink, CancellationToken token) {
        String apiKey = credentials.lookup(credentialEnv(provider));
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new ModelInvocationException(ModelError.AUTH_FAILED,
                    "provider " + provider.id().getValue() + ": 环境变量 " + credentialEnv(provider) + " 未设置");
        }
        Map<String, String> headers = adapter.headers(apiKey);
        String url = adapter.url(provider.apiEndpoint(), model.modelName(), apiKey, sink != null);
        String body = adapter.buildRequest(request, model.modelName(),
                model.contextLimit().getMaxOutputTokens(), sink != null);

        if (sink == null) {
            ChatTransport.Response response = execute(url, headers, body, token);
            if (response.status() >= 200 && response.status() < 300) {
                try {
                    ModelInvocationResult result = adapter.parseResponse(response.body(), model.id());
                    return withUsageFallback(request, model, result);
                } catch (ModelResponseFormatException broken) {
                    // 响应体不是协议约定的 JSON：属于端点行为异常，映射成上下文错误码
                    throw new ModelInvocationException(ModelError.UNAVAILABLE,
                            "模型响应无法解析（" + model.id().getValue() + "）", broken);
                }
            }
            throw mapFailure(response, model);
        }

        final ChatProtocolAdapter.StreamAccumulator accumulator = adapter.newStreamAccumulator();
        try {
            transport.postStreaming(url, headers, body, token, new ChatTransport.LineSink() {
                @Override
                public boolean onData(String data) {
                    boolean more = accumulator.accept(data);
                    if (more) {
                        String delta = accumulator.lastDelta();
                        if (delta != null && !delta.isEmpty()) {
                            sink.accept(delta);
                        }
                    }
                    return more;
                }
            });
        } catch (HttpStatusException status) {
            // 4xx 是确定性错误：交给同一份失败映射，重试只会把同样的非法请求再发一遍
            throw mapFailure(new ChatTransport.Response(status.status(), status.body(), status.retryAfter()), model);
        } catch (RuntimeException e) {
            if (token.isCancelled()) {
                throw new java.util.concurrent.CancellationException("本轮已被终止：不再重试、不降级");
            }
            throw new RetryableFailure("流式读取失败：" + e.getMessage(), null, e);
        }
        return withUsageFallback(request, model, accumulator.result(model.id()));
    }

    /** 厂商不给 usage 时回退到本地计数。 */
    private ModelInvocationResult withUsageFallback(ModelInvocationRequest request, Model model,
                                                     ModelInvocationResult result) {
        if (result.usage() != null) {
            return result;
        }
        int tokens = tokenCounter.count(request.systemPrompt(), request.messages(), model.id());
        return new ModelInvocationResult(result.text(), result.toolCalls(), result.reasoning(),
                new ModelUsage(tokens, 0L, true), result.servedModelId());
    }

    private ChatTransport.Response execute(String url, Map<String, String> headers, String body,
                                          CancellationToken token) {
        try {
            return transport.post(url, headers, body, token);
        } catch (RuntimeException e) {
            if (token.isCancelled()) {
                throw new java.util.concurrent.CancellationException("本轮已被终止：不再重试、不降级");
            }
            throw new RetryableFailure("网络错误：" + e.getMessage(), null, e);
        }
    }

    private ModelInvocationException mapFailure(ChatTransport.Response response, Model model) {
        int status = response.status();
        String reason = "HTTP " + status + "（" + model.id().getValue() + "）";
        if (status == 401 || status == 403) {
            return new ModelInvocationException(ModelError.AUTH_FAILED, reason);
        }
        if (status == 404) {
            return new ModelInvocationException(ModelError.MODEL_NOT_FOUND, reason);
        }
        if (status == 400 && looksLikeContextOverflow(response.body())) {
            return new ModelInvocationException(ModelError.CONTEXT_EXCEEDED, reason + " 上下文超限");
        }
        if (status == 429 || status == 500 || status == 502 || status == 503 || status == 504) {
            throw new RetryableFailure(reason, retryAfterMillis(response));
        }
        return new ModelInvocationException(ModelError.UNAVAILABLE, reason);
    }

    private static boolean looksLikeContextOverflow(String body) {
        if (body == null) {
            return false;
        }
        String lower = body.toLowerCase();
        return lower.contains("context_length") || lower.contains("maximum context")
                || lower.contains("context window") || lower.contains("too many tokens")
                // Anthropic: "prompt is too long"; Gemini: "input token count exceeds the maximum"
                || lower.contains("prompt is too long") || lower.contains("input token count")
                || lower.contains("exceeds the maximum number of tokens");
    }

    private static Long retryAfterMillis(ChatTransport.Response response) {
        String header = response.retryAfter();
        if (header == null || header.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(header.trim()) * 1000L;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long backoff(int attempt) {
        long base = BACKOFF_MILLIS[Math.min(attempt, BACKOFF_MILLIS.length - 1)];
        double factor = 0.8 + jitter.nextDouble() * 0.4;   // ±20%
        return Math.round(base * factor);
    }

    private void sleep(long millis) {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("模型调用等待期间被取消");
        }
    }

    private static String credentialEnv(Provider provider) {
        ApiCredential credential = provider.credential();
        return credential == null ? "" : credential.getApiKeyRef();
    }

    private Model findModel(ModelId modelId) {
        for (Model model : catalog.models()) {
            if (model.id().equals(modelId)) {
                return model;
            }
        }
        return null;
    }

    private Provider findProvider(Model model) {
        for (Provider provider : catalog.providers()) {
            if (provider.id().equals(model.providerId())) {
                return provider;
            }
        }
        return null;
    }

    /** 可重试的失败：外层据此退避重试，重试用尽再交给备用链。 */
    private static final class RetryableFailure extends RuntimeException {

        private final Long retryAfterMillis;

        RetryableFailure(String message, Long retryAfterMillis) {
            super(message);
            this.retryAfterMillis = retryAfterMillis;
        }

        RetryableFailure(String message, Long retryAfterMillis, Throwable cause) {
            super(message, cause);
            this.retryAfterMillis = retryAfterMillis;
        }

        Long retryAfterMillis() {
            return retryAfterMillis;
        }
    }

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    interface CredentialLookup {
        String lookup(String envName);
    }
}
