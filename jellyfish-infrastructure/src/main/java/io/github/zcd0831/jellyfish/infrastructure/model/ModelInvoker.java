package io.github.zcd0831.jellyfish.infrastructure.model;

import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationException;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.action.port.ModelProfile;
import io.github.zcd0831.jellyfish.application.action.port.ModelUsage;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.application.shared.port.TokenCounter;
import io.github.zcd0831.jellyfish.domain.action.model.ApiCredential;
import io.github.zcd0831.jellyfish.domain.action.model.Model;
import io.github.zcd0831.jellyfish.domain.action.model.ModelError;
import io.github.zcd0831.jellyfish.domain.action.model.Provider;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 模型调用的唯一出口：选模型 → 硬校验 → 调用 → 同模型重试 → 备用链降级。
 * 只返回 usage，不记账（模块-行动 §8.2、模块-基础设施层 §10）。
 */
public final class ModelInvoker implements ModelInvocationPort {

    private static final int[] BACKOFF_MILLIS = {500, 1500, 4000};
    private static final long MAX_RETRY_AFTER_SECONDS = 60L;

    private final CatalogRepository catalog;
    private final PolicyRepository policyRepository;
    private final ChatTransport transport;
    private final ProgressPort progress;
    private final TokenCounter tokenCounter;
    private final ChatCompletionsProtocol protocol = new ChatCompletionsProtocol();
    private final Random jitter = new Random();
    private final Sleeper sleeper;
    private final CredentialLookup credentials;

    public ModelInvoker(CatalogRepository catalog, PolicyRepository policyRepository, ChatTransport transport,
                        ProgressPort progress, TokenCounter tokenCounter) {
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
                });
    }

    ModelInvoker(CatalogRepository catalog, PolicyRepository policyRepository, ChatTransport transport,
                 ProgressPort progress, TokenCounter tokenCounter, Sleeper sleeper,
                 CredentialLookup credentials) {
        this.catalog = catalog;
        this.policyRepository = policyRepository;
        this.transport = transport;
        this.progress = progress;
        this.tokenCounter = tokenCounter;
        this.sleeper = sleeper;
        this.credentials = credentials;
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
     * 目标模型失败后按 `modelFallbackChain` 逐个降级，每个模型重新做硬校验（D10、基础设施 §10）。
     * 「请求超限」不降级：交应用层折叠后重试（模块-应用层 §9）。
     */
    private ModelInvocationResult invokeChain(ModelInvocationRequest request, ChunkSink sink) {
        List<ModelId> chain = new ArrayList<>();
        chain.add(request.targetModelId());
        for (ModelId fallback : policyRepository.load().modelFallbackChain()) {
            if (!chain.contains(fallback)) {
                chain.add(fallback);
            }
        }
        ModelInvocationException lastFailure = null;
        for (ModelId modelId : chain) {
            Model model = findModel(modelId);
            if (model == null || !model.isCallable()) {
                continue;
            }
            Provider provider = findProvider(model);
            if (provider == null || !provider.isActive()) {
                continue;
            }
            hardCheck(request.estimatedPromptTokens(), model);
            try {
                return callWithRetry(request, model, provider, sink);
            } catch (ModelInvocationException e) {
                if (e.error() == ModelError.CONTEXT_EXCEEDED) {
                    throw e;
                }
                lastFailure = e;
            }
        }
        throw lastFailure != null ? lastFailure
                : new ModelInvocationException(ModelError.UNAVAILABLE, "没有可调用的模型：" + request.targetModelId().getValue());
    }

    /** 调用侧硬校验：输入与输出是两个独立上限，这里只看输入（模块-行动 §7.3 规则 ②）。 */
    private void hardCheck(int estimatedPromptTokens, Model model) {
        int maxInput = model.contextLimit().getMaxInputTokens();
        if (estimatedPromptTokens > maxInput) {
            throw new ModelInvocationException(ModelError.CONTEXT_EXCEEDED,
                    "请求超限：估算 " + estimatedPromptTokens + " > 上限 " + maxInput + "（" + model.id().getValue() + "）");
        }
    }

    private ModelInvocationResult callWithRetry(ModelInvocationRequest request, Model model, Provider provider,
                                                ChunkSink sink) {
        int attempt = 0;
        while (true) {
            try {
                return callOnce(request, model, provider, sink);
            } catch (RetryableFailure e) {
                if (attempt >= BACKOFF_MILLIS.length) {
                    throw new ModelInvocationException(ModelError.UNAVAILABLE,
                            "重试用尽（" + model.id().getValue() + "）：" + e.getMessage(), e);
                }
                long delay = e.retryAfterMillis() != null ? e.retryAfterMillis() : backoff(attempt);
                if (e.retryAfterMillis() != null && e.retryAfterMillis() > MAX_RETRY_AFTER_SECONDS * 1000) {
                    throw new ModelInvocationException(ModelError.UNAVAILABLE,
                            "Retry-After 超过 60s，直接降级（" + model.id().getValue() + "）", e);
                }
                progress.emit(ProgressEvent.of(ProgressEvent.Kind.RETRY_SCHEDULED,
                        "第 " + (attempt + 1) + " 次重试，" + delay + "ms 后（" + e.getMessage() + "）"));
                sleep(delay);
                attempt++;
            }
        }
    }

    private ModelInvocationResult callOnce(ModelInvocationRequest request, Model model, Provider provider,
                                           ChunkSink sink) {
        String apiKey = credentials.lookup(credentialEnv(provider));
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new ModelInvocationException(ModelError.AUTH_FAILED,
                    "provider " + provider.id().getValue() + ": 环境变量 " + credentialEnv(provider) + " 未设置");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        String url = endpoint(provider);
        String body = protocol.buildRequest(request, model.modelName(), sink != null);

        if (sink == null) {
            ChatTransport.Response response = execute(url, headers, body);
            if (response.status() >= 200 && response.status() < 300) {
                ModelInvocationResult result = protocol.parseResponse(response.body(), model.id());
                return withUsageFallback(request, model, result);
            }
            throw mapFailure(response, model);
        }

        final ChatCompletionsProtocol.StreamAccumulator accumulator = protocol.new StreamAccumulator();
        try {
            transport.postStreaming(url, headers, body, new ChatTransport.LineSink() {
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
        } catch (RuntimeException e) {
            throw new RetryableFailure("流式读取失败：" + e.getMessage(), null, e);
        }
        return withUsageFallback(request, model, accumulator.result(model.id()));
    }

    /** 厂商不给 usage 时回退到本地计数（D06-3）。 */
    private ModelInvocationResult withUsageFallback(ModelInvocationRequest request, Model model,
                                                     ModelInvocationResult result) {
        if (result.usage() != null) {
            return result;
        }
        int tokens = tokenCounter.count(request.systemPrompt(), request.messages(), model.id());
        return new ModelInvocationResult(result.text(), result.toolCalls(),
                new ModelUsage(tokens, 0L), result.servedModelId());
    }

    private ChatTransport.Response execute(String url, Map<String, String> headers, String body) {
        try {
            return transport.post(url, headers, body);
        } catch (RuntimeException e) {
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
                || lower.contains("context window") || lower.contains("too many tokens");
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

    private String endpoint(Provider provider) {
        String base = provider.apiEndpoint();
        return base.endsWith(ChatCompletionsProtocol.PATH) ? base : base + ChatCompletionsProtocol.PATH;
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

    /** 可重试的失败：外层据此退避重试，重试用尽再交给备用链。规则出处：D10 分流表。 */
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
