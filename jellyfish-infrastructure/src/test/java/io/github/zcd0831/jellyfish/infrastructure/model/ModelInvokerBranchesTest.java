package io.github.zcd0831.jellyfish.infrastructure.model;

import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.port.ChatMessage;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationException;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
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

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 端口在"模型/供应商不可用""响应异常"等边界上的分支。 */
class ModelInvokerBranchesTest {

    private static final class Catalog implements CatalogRepository {
        private final List<Model> models;
        private final List<Provider> providers;

        Catalog(List<Model> models, List<Provider> providers) {
            this.models = models;
            this.providers = providers;
        }

        public List<ToolDefinition> tools() { return Collections.emptyList(); }
        public List<AgentDefinition> agents() { return Collections.emptyList(); }
        public List<Provider> providers() { return providers; }
        public List<Model> models() { return models; }
        public List<SkillDefinition> skills() { return Collections.emptyList(); }
        public void saveTool(ToolDefinition tool) { }
        public void saveAgent(AgentDefinition agent) { }
        public void saveProvider(Provider provider) { }
        public void saveModel(Model model) { }
        public void saveSkill(SkillDefinition skill) { }
        public int countActiveSkills() { return 0; }
    }

    private static Model model(String id, ModelStatus status) {
        return new Model(ModelId.of(id), ProviderId.of("p1"), "name", status, new ContextLimit(1000, 100),
                Collections.emptyList());
    }

    private static Provider provider(ProviderStatus status) {
        return new Provider(ProviderId.of("p1"), "p", "http://localhost:1/v1", ChatProtocol.OPENAI_CHAT,
                new ApiCredential("K"), status);
    }

    private static Provider provider(ProviderStatus status, ChatProtocol protocol) {
        return new Provider(ProviderId.of("p1"), "p", "http://localhost:1/v1", protocol,
                new ApiCredential("K"), status);
    }

    private ModelInvoker invoker(Catalog catalog, ChatTransport transport, String apiKey) {
        PolicyRepository policies = new PolicyRepository() {
            public RuntimePolicy load() { return RuntimePolicy.defaults(); }
            public void save(RuntimePolicy p) { }
        };
        TokenCounter counter = new TokenCounter() {
            public int count(String systemPrompt, List<ChatMessage> messages, ModelId modelId) { return 1; }
        };
        return new ModelInvoker(catalog, policies, transport, event -> { }, counter,
                new ModelInvoker.Sleeper() {
                    public void sleep(long millis) { }
                },
                new ModelInvoker.CredentialLookup() {
                    public String lookup(String envName) { return apiKey; }
                });
    }

    private static final class Transport implements ChatTransport {
        private final ChatTransport.Response response;
        private final RuntimeException failure;

        Transport(ChatTransport.Response response, RuntimeException failure) {
            this.response = response;
            this.failure = failure;
        }

        public Response post(String url, Map<String, String> headers, String body) {
            if (failure != null) { throw failure; }
            return response;
        }
        public void postStreaming(String url, Map<String, String> headers, String body, LineSink sink) { }
        @Override
        public boolean cancelledByUser() {
            return false;
        }

        @Override
        public void cancelActive() { }
    }

    private static ModelInvocationRequest request() {
        return new ModelInvocationRequest(ModelId.of("m1"), "sys",
                Collections.singletonList(ChatMessage.of(RecordRole.USER, "hi")), Collections.emptyList(), 1, null);
    }

    @Test
    void 模型不存在即报模型缺失() {
        ModelInvoker invoker = invoker(new Catalog(Collections.<Model>emptyList(),
                Collections.<Provider>emptyList()), new Transport(new ChatTransport.Response(200, "{}", null), null), "k");

        ModelInvocationException e = assertThrows(ModelInvocationException.class,
                () -> invoker.invoke(request()));
        assertEquals(ModelError.UNAVAILABLE, e.error());

        ModelInvocationException describe = assertThrows(ModelInvocationException.class,
                () -> invoker.describe(ModelId.of("m1")));
        assertEquals(ModelError.MODEL_NOT_FOUND, describe.error());
    }

    @Test
    void 供应商不可用或弃用时不调用() {
        Catalog catalog = new Catalog(Collections.singletonList(model("m1", ModelStatus.ACTIVE)),
                Collections.singletonList(provider(ProviderStatus.DEPRECATED)));

        assertThrows(ModelInvocationException.class,
                () -> invoker(catalog, new Transport(new ChatTransport.Response(200, "{}", null), null), "k")
                        .invoke(request()));
    }

    @Test
    void 无法解析的响应按不可重试失败() {
        Catalog catalog = new Catalog(Collections.singletonList(model("m1", ModelStatus.ACTIVE)),
                Collections.singletonList(provider(ProviderStatus.ACTIVE)));

        assertThrows(ModelInvocationException.class,
                () -> invoker(catalog, new Transport(new ChatTransport.Response(200, "不是JSON", null), null), "k")
                        .invoke(request()));
    }

    @Test
    void 上下文超限不降级直接抛出() {
        Catalog catalog = new Catalog(Collections.singletonList(model("m1", ModelStatus.ACTIVE)),
                Collections.singletonList(provider(ProviderStatus.ACTIVE)));

        ModelInvocationException e = assertThrows(ModelInvocationException.class,
                () -> invoker(catalog, new Transport(new ChatTransport.Response(400,
                        "{\"error\":\"maximum context length exceeded\"}", null), null), "k").invoke(request()));
        assertEquals(ModelError.CONTEXT_EXCEEDED, e.error());
    }

    @Test
    void Anthropic与Gemini的超限文案也映射为上下文超限() {
        Catalog catalog = new Catalog(Collections.singletonList(model("m1", ModelStatus.ACTIVE)),
                Collections.singletonList(provider(ProviderStatus.ACTIVE)));

        ModelInvocationException anthropic = assertThrows(ModelInvocationException.class,
                () -> invoker(catalog, new Transport(new ChatTransport.Response(400,
                        "{\"error\":{\"message\":\"prompt is too long: 250000 tokens > 200000 maximum\"}}",
                        null), null), "k").invoke(request()));
        assertEquals(ModelError.CONTEXT_EXCEEDED, anthropic.error());

        ModelInvocationException gemini = assertThrows(ModelInvocationException.class,
                () -> invoker(catalog, new Transport(new ChatTransport.Response(400,
                        "{\"error\":{\"message\":\"The input token count (250000) exceeds the maximum "
                                + "number of tokens allowed (200000).\"}}", null), null), "k").invoke(request()));
        assertEquals(ModelError.CONTEXT_EXCEEDED, gemini.error());
    }

    @Test
    void 未识别的状态码按不可用处理() {
        Catalog catalog = new Catalog(Collections.singletonList(model("m1", ModelStatus.ACTIVE)),
                Collections.singletonList(provider(ProviderStatus.ACTIVE)));

        ModelInvocationException e = assertThrows(ModelInvocationException.class,
                () -> invoker(catalog, new Transport(new ChatTransport.Response(418, "{}", null), null), "k")
                        .invoke(request()));
        assertEquals(ModelError.UNAVAILABLE, e.error());
    }

    @Test
    void Anthropic协议走自己的端点与请求头() {
        Catalog catalog = new Catalog(Collections.singletonList(
                new Model(ModelId.of("m1"), ProviderId.of("p1"), "claude-sonnet-4-5", ModelStatus.ACTIVE,
                        new ContextLimit(1000, 100), Collections.emptyList())),
                Collections.singletonList(provider(ProviderStatus.ACTIVE, ChatProtocol.ANTHROPIC_MESSAGES)));
        final String[] seen = new String[3];
        ChatTransport transport = new ChatTransport() {
            public Response post(String url, Map<String, String> headers, String body) {
                seen[0] = url;
                seen[1] = headers.get("x-api-key");
                seen[2] = body;
                return new Response(200, "{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                        + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}", null);
            }

            public void postStreaming(String url, Map<String, String> headers, String body, LineSink sink) { }

            public void cancelActive() { }

            public boolean cancelledByUser() {
                return false;
            }
        };

        invoker(catalog, transport, "secret").invoke(request());

        assertTrue(seen[0].endsWith("/v1/messages"), seen[0]);
        assertEquals("secret", seen[1]);
        assertTrue(seen[2].contains("\"max_tokens\""), "Anthropic 必填 max_tokens");
    }

    @Test
    void Gemini协议走自己的端点与请求头() {
        Catalog catalog = new Catalog(Collections.singletonList(
                new Model(ModelId.of("m1"), ProviderId.of("p1"), "gemini-2.5-pro", ModelStatus.ACTIVE,
                        new ContextLimit(1000, 100), Collections.emptyList())),
                Collections.singletonList(new Provider(ProviderId.of("p1"), "p",
                        "https://generativelanguage.googleapis.com", ChatProtocol.GEMINI,
                        new ApiCredential("K"), ProviderStatus.ACTIVE)));
        final String[] seen = new String[2];
        ChatTransport transport = new ChatTransport() {
            public Response post(String url, Map<String, String> headers, String body) {
                seen[0] = url;
                seen[1] = headers.get("x-goog-api-key");
                return new Response(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]}}],"
                        + "\"usageMetadata\":{\"promptTokenCount\":1,\"candidatesTokenCount\":1}}", null);
            }

            public void postStreaming(String url, Map<String, String> headers, String body, LineSink sink) { }

            public void cancelActive() { }

            public boolean cancelledByUser() {
                return false;
            }
        };

        invoker(catalog, transport, "secret").invoke(request());

        assertTrue(seen[0].contains("/v1beta/models/gemini-2.5-pro:generateContent"), seen[0]);
        assertEquals("secret", seen[1]);
    }

    @Test
    void 流式的4xx不重试且保留错误码() {
        Catalog catalog = new Catalog(Collections.singletonList(model("m1", ModelStatus.ACTIVE)),
                Collections.singletonList(provider(ProviderStatus.ACTIVE)));
        final int[] attempts = {0};
        ChatTransport streaming = new ChatTransport() {
            public Response post(String url, Map<String, String> headers, String body) {
                throw new UnsupportedOperationException("流式用例不该走阻塞路径");
            }

            public void postStreaming(String url, Map<String, String> headers, String body, LineSink sink) {
                attempts[0]++;
                throw new HttpStatusException(401, "{\"error\":\"bad key\"}", null);
            }

            public void cancelActive() { }

            public boolean cancelledByUser() {
                return false;
            }
        };

        ModelInvocationException e = assertThrows(ModelInvocationException.class,
                () -> invoker(catalog, streaming, "k").invokeStreaming(request(), ChunkSink.NOOP));

        assertEquals(ModelError.AUTH_FAILED, e.error());
        assertEquals(1, attempts[0], "401 是确定性错误，不该退避重试");
    }

    @Test
    void 流式的超限错误映射为上下文超限() {
        Catalog catalog = new Catalog(Collections.singletonList(model("m1", ModelStatus.ACTIVE)),
                Collections.singletonList(provider(ProviderStatus.ACTIVE)));
        ChatTransport streaming = new ChatTransport() {
            public Response post(String url, Map<String, String> headers, String body) {
                throw new UnsupportedOperationException();
            }

            public void postStreaming(String url, Map<String, String> headers, String body, LineSink sink) {
                throw new HttpStatusException(400, "{\"error\":\"maximum context length exceeded\"}", null);
            }

            public void cancelActive() { }

            public boolean cancelledByUser() {
                return false;
            }
        };

        ModelInvocationException e = assertThrows(ModelInvocationException.class,
                () -> invoker(catalog, streaming, "k").invokeStreaming(request(), ChunkSink.NOOP));

        assertEquals(ModelError.CONTEXT_EXCEEDED, e.error());
    }

    @Test
    void 无usage时用本地计数兜底() {
        Catalog catalog = new Catalog(Collections.singletonList(model("m1", ModelStatus.ACTIVE)),
                Collections.singletonList(provider(ProviderStatus.ACTIVE)));

        io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult result = invoker(catalog,
                new Transport(new ChatTransport.Response(200,
                        "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}", null), null), "k")
                .invoke(request());

        assertEquals(1L, result.usage().inputTokens());
    }
}
