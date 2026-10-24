package io.github.zcd0831.jellyfish.infrastructure.model;

import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationException;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.application.shared.port.TokenCounter;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition;
import io.github.zcd0831.jellyfish.domain.action.model.ApiCredential;
import io.github.zcd0831.jellyfish.domain.action.model.ChatProtocol;
import io.github.zcd0831.jellyfish.domain.action.model.ContextLimit;
import io.github.zcd0831.jellyfish.domain.action.model.Model;
import io.github.zcd0831.jellyfish.domain.action.model.ModelError;
import io.github.zcd0831.jellyfish.domain.action.model.ModelStatus;
import io.github.zcd0831.jellyfish.domain.action.model.Provider;
import io.github.zcd0831.jellyfish.domain.action.model.ProviderStatus;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolDefinition;
import io.github.zcd0831.jellyfish.domain.memory.skill.SkillDefinition;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.ProviderId;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重试 / 降级 / 硬校验 / 错误分流；不发真实请求（假传输层）。
 * 规则出处：技术决策 D10、D10.1、D11，模块-基础设施层 §10。
 */
class ModelInvokerTest {

    private static final String OK_BODY = "{\"choices\":[{\"message\":{\"content\":\"答复\"}}],"
            + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}";

    private final ScriptedTransport transport = new ScriptedTransport();
    private final FakeProgress progress = new FakeProgress();
    private final List<Long> sleeps = new ArrayList<>();
    private final List<String> requestedUrls = new ArrayList<>();

    // ---------- 假端口 ----------

    private static final class ScriptedTransport implements ChatTransport {

        private final Deque<Object> script = new ArrayDeque<>();
        private final List<Map<String, String>> headers = new ArrayList<>();
        private final List<String> urls = new ArrayList<>();
        private final List<String> bodies = new ArrayList<>();
        private List<String> streamLines = Collections.emptyList();
        private int calls;

        void respond(Response response) {
            script.add(response);
        }

        void failWith(RuntimeException failure) {
            script.add(failure);
        }

        void streamWith(List<String> lines) {
            this.streamLines = lines;
        }

        @Override
        public Response post(String url, Map<String, String> headers, String body) {
            calls++;
            this.urls.add(url);
            this.bodies.add(body);
            this.headers.add(headers);
            Object next = script.poll();
            if (next instanceof RuntimeException) {
                throw (RuntimeException) next;
            }
            return (Response) next;
        }

        private boolean cancelled;

        @Override
        public boolean cancelledByUser() {
            return cancelled;
        }

        @Override
        public void cancelActive() {
        }

        @Override
        public void postStreaming(String url, Map<String, String> headers, String body, LineSink sink) {
            calls++;
            this.urls.add(url);
            this.headers.add(headers);
            for (String line : streamLines) {
                if (!sink.onData(line)) {
                    return;
                }
            }
        }
    }

    private static final class FakeProgress implements ProgressPort {

        private final List<ProgressEvent> events = new ArrayList<>();

        @Override
        public void emit(ProgressEvent event) {
            events.add(event);
        }
    }

    private static final class FakeCatalog implements CatalogRepository {

        private final List<Model> models = new ArrayList<>();
        private final List<Provider> providers = new ArrayList<>();

        @Override
        public List<ToolDefinition> tools() {
            return Collections.emptyList();
        }

        @Override
        public List<AgentDefinition> agents() {
            return Collections.emptyList();
        }

        @Override
        public List<Provider> providers() {
            return providers;
        }

        @Override
        public List<Model> models() {
            return models;
        }

        @Override
        public List<SkillDefinition> skills() {
            return Collections.emptyList();
        }

        @Override
        public void saveTool(ToolDefinition tool) {
        }

        @Override
        public void saveAgent(AgentDefinition agent) {
        }

        @Override
        public void saveProvider(Provider provider) {
        }

        @Override
        public void saveModel(Model model) {
        }

        @Override
        public void saveSkill(SkillDefinition skill) {
        }
    }

    // ---------- 夹具 ----------

    private static Model model(String id, int maxInput) {
        return new Model(ModelId.of(id), ProviderId.of("p1"), "model-" + id, ModelStatus.ACTIVE,
                new ContextLimit(maxInput, 4096), Collections.emptyList());
    }

    private static Provider provider() {
        return new Provider(ProviderId.of("p1"), "测试供应商", "http://localhost:1/v1", ChatProtocol.OPENAI_CHAT,
                new ApiCredential("TEST_API_KEY"), ProviderStatus.ACTIVE);
    }

    private static RuntimePolicy policyWithFallback(ModelId... fallback) {
        RuntimePolicy d = RuntimePolicy.defaults();
        return new RuntimePolicy(d.maxRetries(), d.reflectionThreshold(), d.maxReplanGeneration(),
                d.maxReActRounds(), d.summarizationTokenThreshold(), d.inputSafetyMargin(),
                d.contextWindowRecordCount(), d.systemRecordRatioLimit(), d.toolResultTruncateLength(),
                d.subagentExecutionTimeoutMillis(), d.experienceCapacity(), d.experienceTopN(),
                d.experiencePerPlan(), d.experienceInitialConfidence(), d.experienceConfidenceStep(),
                d.experienceRetireThreshold(), d.budgetWarnRatio(), d.budgetLimitTokens(),
                Arrays.asList(fallback), d.trustedToolIds());
    }

    private ModelInvoker invoker(FakeCatalog catalog, RuntimePolicy policy, String apiKey) {
        PolicyRepository policyRepository = new PolicyRepository() {
            @Override
            public RuntimePolicy load() {
                return policy;
            }

            @Override
            public void save(RuntimePolicy p) {
            }
        };
        TokenCounter counter = new TokenCounter() {
            @Override
            public int count(String systemPrompt, List<ChatMessage> messages, ModelId modelId) {
                return 42;
            }
        };
        return new ModelInvoker(catalog, policyRepository, transport, progress, counter,
                new ModelInvoker.Sleeper() {
                    @Override
                    public void sleep(long millis) {
                        sleeps.add(millis);
                    }
                },
                new ModelInvoker.CredentialLookup() {
                    @Override
                    public String lookup(String envName) {
                        return apiKey;
                    }
                });
    }

    private static ModelInvocationRequest request(String modelId, int estimatedTokens) {
        return new ModelInvocationRequest(ModelId.of(modelId), "你是助手",
                Arrays.asList(ChatMessage.of(RecordRole.USER, "你好")), Collections.emptyList(),
                estimatedTokens, null);
    }

    // ---------- 用例 ----------

    @Test
    void 成功调用返回文本并带上凭据与路径() {
        FakeCatalog catalog = new FakeCatalog();
        catalog.models.add(model("m1", 1000));
        catalog.providers.add(provider());
        transport.respond(new ChatTransport.Response(200, OK_BODY, null));

        ModelInvocationResult result = invoker(catalog, policyWithFallback(), "sk-test")
                .invoke(request("m1", 10));

        assertEquals("答复", result.text());
        assertEquals(ModelId.of("m1"), result.servedModelId());
        assertEquals("http://localhost:1/v1/chat/completions", transport.urls.get(0));
        assertEquals("Bearer sk-test", transport.headers.get(0).get("Authorization"));
        assertTrue(transport.bodies.get(0).contains("你是助手"));
    }

    @Test
    void 硬校验不通过时不发请求() {
        FakeCatalog catalog = new FakeCatalog();
        catalog.models.add(model("m1", 100));
        catalog.providers.add(provider());

        ModelInvocationException e = assertThrows(ModelInvocationException.class,
                () -> invoker(catalog, policyWithFallback(), "sk-test").invoke(request("m1", 1000)));

        assertEquals(ModelError.CONTEXT_EXCEEDED, e.error());
        assertEquals(0, transport.calls);
    }

    @Test
    void 凭据缺失直接判认证失败() {
        FakeCatalog catalog = new FakeCatalog();
        catalog.models.add(model("m1", 1000));
        catalog.providers.add(provider());

        ModelInvocationException e = assertThrows(ModelInvocationException.class,
                () -> invoker(catalog, policyWithFallback(), null).invoke(request("m1", 10)));

        assertEquals(ModelError.AUTH_FAILED, e.error());
        assertTrue(e.getMessage().contains("TEST_API_KEY"));
    }

    @Test
    void 认证失败不重试且降级到备用模型() {
        FakeCatalog catalog = new FakeCatalog();
        catalog.models.add(model("m1", 1000));
        catalog.models.add(model("m2", 1000));
        catalog.providers.add(provider());
        transport.respond(new ChatTransport.Response(401, "{}", null));
        transport.respond(new ChatTransport.Response(200, OK_BODY, null));

        ModelInvocationResult result = invoker(catalog, policyWithFallback(ModelId.of("m2")), "sk-test")
                .invoke(request("m1", 10));

        assertEquals(ModelId.of("m2"), result.servedModelId());
        assertEquals(2, transport.calls);
    }

    @Test
    void 服务端错误重试三次后放弃() {
        FakeCatalog catalog = new FakeCatalog();
        catalog.models.add(model("m1", 1000));
        catalog.providers.add(provider());
        for (int i = 0; i < 4; i++) {
            transport.respond(new ChatTransport.Response(503, "{}", null));
        }

        ModelInvocationException e = assertThrows(ModelInvocationException.class,
                () -> invoker(catalog, policyWithFallback(), "sk-test").invoke(request("m1", 10)));

        assertEquals(ModelError.UNAVAILABLE, e.error());
        assertEquals(4, transport.calls);
        assertEquals(3, sleeps.size());
        assertEquals(ProgressEvent.Kind.RETRY_SCHEDULED, progress.events.get(0).kind());
    }

    @Test
    void 用户取消不进重试() {
        FakeCatalog catalog = new FakeCatalog();
        catalog.models.add(model("m1", 1000));
        catalog.providers.add(provider());
        transport.cancelled = true;
        transport.failWith(new java.io.UncheckedIOException("canceled", new java.io.IOException("canceled")));

        assertThrows(java.util.concurrent.CancellationException.class,
                () -> invoker(catalog, policyWithFallback(), "sk-test").invoke(request("m1", 10)));

        assertEquals(1, transport.calls, "取消是终态：不得重试");
        assertTrue(sleeps.isEmpty(), "取消不应进入退避等待");
    }

    @Test
    void RetryAfter超过60秒直接降级不等待() {
        FakeCatalog catalog = new FakeCatalog();
        catalog.models.add(model("m1", 1000));
        catalog.models.add(model("m2", 1000));
        catalog.providers.add(provider());
        transport.respond(new ChatTransport.Response(429, "{}", "120"));
        transport.respond(new ChatTransport.Response(200, OK_BODY, null));

        ModelInvocationResult result = invoker(catalog, policyWithFallback(ModelId.of("m2")), "sk-test")
                .invoke(request("m1", 10));

        assertEquals(ModelId.of("m2"), result.servedModelId());
        assertEquals(0, sleeps.size());
    }

    @Test
    void 流式增量交给sink且返回值是完整文本() {
        FakeCatalog catalog = new FakeCatalog();
        catalog.models.add(model("m1", 1000));
        catalog.providers.add(provider());
        transport.streamWith(Arrays.asList(
                "{\"choices\":[{\"delta\":{\"content\":\"你\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"好\"}}]}",
                "[DONE]"));
        final StringBuilder seen = new StringBuilder();

        ModelInvocationResult result = invoker(catalog, policyWithFallback(), "sk-test")
                .invokeStreaming(request("m1", 10), new ChunkSink() {
                    @Override
                    public void accept(String delta) {
                        seen.append(delta);
                    }
                });

        assertEquals("你好", seen.toString());
        assertEquals("你好", result.text());
    }

    @Test
    void 厂商不给usage时用本地计数兜底() {
        FakeCatalog catalog = new FakeCatalog();
        catalog.models.add(model("m1", 1000));
        catalog.providers.add(provider());
        transport.respond(new ChatTransport.Response(200,
                "{\"choices\":[{\"message\":{\"content\":\"答复\"}}]}", null));

        ModelInvocationResult result = invoker(catalog, policyWithFallback(), "sk-test").invoke(request("m1", 10));

        assertEquals(42L, result.usage().inputTokens());
    }
}
