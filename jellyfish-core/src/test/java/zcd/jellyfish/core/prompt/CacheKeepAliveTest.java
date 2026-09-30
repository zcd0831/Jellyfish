package zcd.jellyfish.core.prompt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.infra.config.Model;
import zcd.jellyfish.infra.config.Provider;
import zcd.jellyfish.infra.config.ProviderCacheSettings;
import zcd.jellyfish.infra.config.ReactSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.llm.LlmClient;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmRequest;
import zcd.jellyfish.infra.llm.LlmResponse;
import zcd.jellyfish.infra.llm.LlmUsage;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.model.ResolvedModel;
import zcd.jellyfish.infra.model.SessionModelResolver;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CacheKeepAlive} 的单元测试。
 * <p>
 * 直接驱动 {@link CacheKeepAlive#tick(long)} 而不是去等真的调度器：时间相关的东西一旦只能靠等，
 * 用例就会变得又慢又飘。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("缓存保活")
class CacheKeepAliveTest {

    /** 一个任意的「现在」。 */
    private static final long NOW = 1_000_000L;

    /** 会话域服务。 */
    @Mock
    private SessionManager sessionManager;

    /** 会话模型解析器。 */
    @Mock
    private SessionModelResolver sessionModelResolver;

    /** 模型门面。 */
    @Mock
    private ModelManager modelManager;

    /** 提示词组装器。 */
    @Mock
    private PromptAssembler promptAssembler;

    /** 运行时配置门面。 */
    @Mock
    private RuntimeConfig runtimeConfig;

    /** 客户端。 */
    @Mock
    private LlmClient client;

    /** 被测保活器。 */
    private CacheKeepAlive keepAlive;

    @BeforeEach
    void setUp() {
        keepAlive = new CacheKeepAlive(sessionManager, sessionModelResolver, modelManager,
                promptAssembler, runtimeConfig);
    }

    @AfterEach
    void tearDown() {
        keepAlive.close();
    }

    @Test
    @DisplayName("缺省关闭：间隔为 0 时一个请求都不发")
    void tick_should_doNothing_whenDisabled() {
        // Given
        Session session = givenSession(3);
        givenKeepAliveSeconds(session, 0);

        // When
        keepAlive.tick(NOW);

        // Then：不发请求、不记账——这是它作为可选项的全部意义
        verify(client, never()).chat(any(LlmRequest.class));
        verify(sessionManager, never()).recordUsage(any(String.class), any(LlmUsage.class));
    }

    @Test
    @DisplayName("空闲到点才发：第一次 tick 只开始计时")
    void tick_should_startCounting_beforeSendingAnything() {
        // Given：间隔 240 秒
        Session session = givenSession(3);
        givenKeepAliveSeconds(session, 240);

        // When：刚刚发现这个会话安静下来
        keepAlive.tick(NOW);

        // Then：还没到点，什么都不发
        verify(client, never()).chat(any(LlmRequest.class));
    }

    @Test
    @DisplayName("空闲够久就续一次，并把用量如实记账")
    void tick_should_send_whenIdleLongEnough() {
        // Given
        Session session = givenSession(3);
        givenKeepAliveSeconds(session, 240);
        LlmRequest request = LlmRequest.builder("gpt-4o").message(LlmMessage.user("keep")).build();
        when(promptAssembler.buildKeepAlive(any(Session.class), any(ResolvedModel.class), any(String.class)))
                .thenReturn(request);
        when(client.chat(request)).thenReturn(new LlmResponse("ok", null,
                java.util.Collections.<zcd.jellyfish.infra.llm.LlmToolCall>emptyList(),
                new LlmUsage(5000, 1, 5001), "stop"));
        keepAlive.tick(NOW);

        // When
        keepAlive.tick(NOW + 240_000L);

        // Then：保活花掉的 token 必须进账，否则 /usage 与实际账单对不上。
        // 用 captor 而不是直接比对象：LlmUsage 是值类但没有 equals，直接比只会比到身份
        verify(client).chat(request);
        ArgumentCaptor<LlmUsage> recorded = ArgumentCaptor.forClass(LlmUsage.class);
        verify(sessionManager).recordUsage(eq(session.getSessionId()), recorded.capture());
        assertEquals(5001, recorded.getValue().getTotalTokens());
    }

    @Test
    @DisplayName("每个空闲期最多续三次：再往后就是亏的")
    void tick_should_stopAfterMaxRounds() {
        // Given
        Session session = givenSession(3);
        givenKeepAliveSeconds(session, 60);
        when(promptAssembler.buildKeepAlive(any(Session.class), any(ResolvedModel.class), any(String.class)))
                .thenReturn(LlmRequest.builder("gpt-4o").message(LlmMessage.user("keep")).build());

        // When：连续五个间隔都没人动过
        for (int round = 1; round <= 5; round++) {
            keepAlive.tick(NOW + round * 60_000L);
        }

        // Then：只发了三次。一次命中约 0.1× 前缀，三次约 0.3×，换掉一次未命中（0.9×）就净赚；
        // 再往下续则开始有亏的风险，而那时用户多半已经离开
        verify(client, org.mockito.Mockito.times(ProviderCacheSettings.MAX_KEEP_ALIVE_ROUNDS))
                .chat(any(LlmRequest.class));
    }

    @Test
    @DisplayName("用户一动，空闲期重新开始：额度也跟着重置")
    void tick_should_resetIdlePeriod_whenSessionGrows() {
        // Given：已经续满三次
        Session session = givenSession(3);
        givenKeepAliveSeconds(session, 60);
        when(promptAssembler.buildKeepAlive(any(Session.class), any(ResolvedModel.class), any(String.class)))
                .thenReturn(LlmRequest.builder("gpt-4o").message(LlmMessage.user("keep")).build());
        keepAlive.tick(NOW);
        for (int round = 1; round <= 3; round++) {
            keepAlive.tick(NOW + round * 60_000L);
        }

        // When：用户又聊了一轮（消息条数变了），随后再次空闲
        when(session.getMessages()).thenReturn(java.util.Collections.nCopies(5,
                zcd.jellyfish.infra.session.SessionMessage.of(LlmMessage.user("x"))));
        keepAlive.tick(NOW + 300_000L);
        keepAlive.tick(NOW + 400_000L);

        // Then：新的一轮空闲有它自己的额度，于是又发了一次（总计四次）
        verify(client, org.mockito.Mockito.times(4)).chat(any(LlmRequest.class));
    }

    @Test
    @DisplayName("复现不出前缀就放弃：发一个前缀不同的请求比不保活更贵")
    void tick_should_skip_whenPrefixCannotBeReproduced() {
        // Given：组装器说这条会话复现不出父请求的前缀
        Session session = givenSession(3);
        givenKeepAliveSeconds(session, 60);
        when(promptAssembler.buildKeepAlive(any(Session.class), any(ResolvedModel.class), any(String.class)))
                .thenReturn(null);

        // When
        keepAlive.tick(NOW);
        keepAlive.tick(NOW + 60_000L);

        // Then
        verify(client, never()).chat(any(LlmRequest.class));
    }

    @Test
    @DisplayName("模型解析失败只静默跳过：保活没资格因此打扰用户")
    void tick_should_skipSilently_whenModelCannotBeResolved() {
        // Given
        Session session = givenSession(3);
        when(sessionManager.current()).thenReturn(session);
        lenient().when(sessionModelResolver.resolve(session)).thenThrow(new IllegalStateException("没配模型"));

        // When
        keepAlive.tick(NOW);

        // Then
        verify(client, never()).chat(any(LlmRequest.class));
    }

    @Test
    @DisplayName("没有当前会话时什么都不做")
    void tick_should_doNothing_whenNoSession() {
        // Given
        when(sessionManager.current()).thenReturn(null);

        // When
        keepAlive.tick(NOW);

        // Then
        verify(promptAssembler, never()).buildKeepAlive(any(Session.class), any(ResolvedModel.class),
                any(String.class));
    }

    /**
     * 准备一个「有 3 条消息」的当前会话，并解析出它用的模型。
     *
     * @param messageCount 消息条数
     * @return 会话
     */
    private Session givenSession(int messageCount) {
        Session session = org.mockito.Mockito.mock(Session.class);
        lenient().when(session.getSessionId()).thenReturn("s-1");
        lenient().when(session.getMessages()).thenReturn(java.util.Collections.nCopies(messageCount,
                zcd.jellyfish.infra.session.SessionMessage.of(LlmMessage.user("x"))));
        lenient().when(sessionManager.current()).thenReturn(session);
        lenient().when(sessionModelResolver.resolve(session)).thenReturn(resolvedModel(new ProviderCacheSettings()));
        return session;
    }

    /**
     * 把保活间隔写进「这个会话用的 provider」的缓存段。
     *
     * @param session          会话
     * @param keepAliveSeconds 间隔秒数
     */
    private void givenKeepAliveSeconds(Session session, int keepAliveSeconds) {
        lenient().when(sessionModelResolver.resolve(session))
                .thenReturn(resolvedModel(new ProviderCacheSettings(null, keepAliveSeconds)));
        lenient().when(modelManager.getClient(any(ResolvedModel.class))).thenReturn(client);
    }

    /**
     * 构造解析后的模型。
     *
     * @param cache 缓存治理段
     * @return 解析结果
     */
    private static ResolvedModel resolvedModel(ProviderCacheSettings cache) {
        Provider provider = new Provider("openai", "openai", null, null, null, cache);
        return new ResolvedModel(provider, new Model("gpt-4o", "gpt-4o", 128_000, 4096));
    }
}
