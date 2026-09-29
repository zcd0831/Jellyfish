package zcd.jellyfish.core.compact;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.extension.CompactionStrategy;
import zcd.jellyfish.api.extension.CompactionStrategyRequest;
import zcd.jellyfish.api.extension.CompactionTrigger;
import zcd.jellyfish.api.extension.PermissionMode;
import zcd.jellyfish.api.extension.SessionCompactionSnapshot;
import zcd.jellyfish.api.extension.SessionMessageSnapshot;
import zcd.jellyfish.api.extension.SessionRestoreRequest;
import zcd.jellyfish.api.extension.SessionRestoreResult;
import zcd.jellyfish.api.extension.SessionSnapshot;
import zcd.jellyfish.core.prompt.ContextUsage;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.Model;
import zcd.jellyfish.infra.config.Provider;
import zcd.jellyfish.infra.config.ReactSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.llm.LlmClient;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmRequest;
import zcd.jellyfish.infra.llm.LlmResponse;
import zcd.jellyfish.infra.llm.LlmToolCall;
import zcd.jellyfish.infra.llm.LlmUsage;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.model.SessionModelResolver;
import zcd.jellyfish.infra.model.ResolvedModel;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.infra.session.SessionDefaults;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ConversationCompactor} 的单元测试。
 * <p>
 * 用<b>真实</b>的 {@code SessionManager}（压缩的全部意义就是「会话被改成了什么样」）、真实的
 * {@link ExtensionRegistry}（插件策略走的就是它）与真实的单线程执行器（同步跑完，避免用
 * {@code Thread.sleep} 猜时序），只 mock 模型侧。
 * <p>
 * <b>压缩策略由插件提供</b>，因此本测试的扩展点策略是真的：{@code setUp} 里先登记一个
 * <b>order 很大</b>的兜底策略，让绝大多数用例（关心的是压缩逻辑本身）有指令可用，而个别用例自己登记
 * 的处理器天然排在它前面、按「逐字段取 order 最小者」的规则胜出。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("会话压缩器")
class ConversationCompactorTest {

    /** 兜底策略的摘要指令：真正的措辞由插件给，这里只求「有指令可用」。 */
    private static final String FALLBACK_PROMPT = "把历史压成摘要，不超过 {maxSummaryChars} 字。";

    /** 恢复用例里那份「手工改过、边界指向不存在的消息」的会话标识。 */
    private static final String RESTORED_SESSION_ID = "restored-1";

    /** agent 门面，仅用于构造真实 SessionManager。 */
    @Mock
    private AgentManager agentManager;

    /** 通知发布入口，仅用于构造真实 SessionManager。 */
    @Mock
    private EventPublisher events;

    /** 模型门面。 */
    @Mock
    private ModelManager modelManager;

    /** 运行时配置门面。 */
    @Mock
    private RuntimeConfig runtimeConfig;

    /** LLM 客户端。 */
    @Mock
    private LlmClient client;

    /** 真实会话域服务。 */
    private SessionManager sessionManager;

    /** 真实同步扩展点策略。 */
    private ExtensionRegistry extensions;

    /** 单线程执行器：提交即在本线程跑完，因此断言不必等。 */
    private ExecutorService executor;

    /** 被测压缩器。 */
    private ConversationCompactor compactor;

    /** 本用例真实建出来的会话标识（会话标识是 UUID，只能现取）。 */
    private String createdSessionId;

    @BeforeEach
    void setUp() {
        extensions = new ExtensionRegistry(new TypeRegistry());
        sessionManager = new SessionManager(agentManager, events, extensions, new SessionDefaults());
        executor = Executors.newSingleThreadExecutor();
        compactor = newCompactor(executor);
        // 兜底策略：order 排在最后，任何用例自己登记的处理器都排在它前面
        extensions.contribute("fallback", CompactionStrategyRequest.class, null,
                request -> new CompactionStrategy(FALLBACK_PROMPT, null, null),
                RegisterOptions.order(100));
    }

    @Test
    @DisplayName("没有插件登记策略时压缩整体不可用：isAvailable 为假、计划直接报错")
    void plan_should_reportUnavailable_when_noStrategyRegistered() {
        sessionWithMessages(6);
        givenModel(128_000, 4_000);
        // 换一个干净注册表：模拟「一个压缩插件都没装」
        ConversationCompactor bare = new ConversationCompactor(sessionManager, modelManager, runtimeConfig,
                new ExtensionRegistry(new TypeRegistry()), events,
                new SessionModelResolver(modelManager, agentManager), executor);

        assertFalse(bare.isAvailable());
        CompactionUnavailableException error = assertThrows(CompactionUnavailableException.class,
                () -> bare.plan(createdSessionId));
        assertTrue(error.getMessage().contains("没有插件提供压缩策略"), error.getMessage());
    }

    @Test
    @DisplayName("有处理器但什么都没给：同样不可用，且提示指向那个坏插件")
    void plan_should_reportUnavailable_when_strategyHasNoPrompt() {
        sessionWithMessages(6);
        givenModel(128_000, 4_000);
        ExtensionRegistry silent = new ExtensionRegistry(new TypeRegistry());
        silent.contribute("silent", CompactionStrategyRequest.class, null,
                request -> CompactionStrategy.none(), RegisterOptions.DEFAULT);
        ConversationCompactor bare = new ConversationCompactor(sessionManager, modelManager, runtimeConfig,
                silent, events, new SessionModelResolver(modelManager, agentManager), executor);

        assertTrue(bare.isAvailable());
        CompactionUnavailableException error = assertThrows(CompactionUnavailableException.class,
                () -> bare.plan(createdSessionId));
        assertTrue(error.getMessage().contains("摘要指令"), error.getMessage());
    }

    @Test
    @DisplayName("自动压缩在没插件时静默让路，但只提醒一次")
    void autoCompactIfNeeded_should_warnOnce_when_noStrategyRegistered() {
        sessionWithMessages(6);
        // 自动那一路只在「真该压了」之后才看可用性，因此 react 段必须有值
        lenient().when(runtimeConfig.getReactSettings()).thenReturn(new ReactSettings());
        ConversationCompactor bare = new ConversationCompactor(sessionManager, modelManager, runtimeConfig,
                new ExtensionRegistry(new TypeRegistry()), events,
                new SessionModelResolver(modelManager, agentManager), executor);
        ContextUsage usage = new ContextUsage(900, 1000, false);

        assertFalse(bare.autoCompactIfNeeded(createdSessionId, usage));
        assertFalse(bare.autoCompactIfNeeded(createdSessionId, usage));

        // 一次 WARN + 一次 ConfigWarningEvent：多了会把日志刷满，少了用户不知道历史正在被裁掉
        verify(events, times(1)).publish(any(ConfigWarningEvent.class));
        verify(client, never()).chat(any(LlmRequest.class));
    }

    @Test
    @DisplayName("摘要指令缺占位符不致命：照发，只是模型拿不到长度约束")
    void plan_should_keepGoing_when_promptHasNoPlaceholder() {
        sessionWithMessages(6);
        givenModel(128_000, 4_000);
        extensions.contribute("no-placeholder", CompactionStrategyRequest.class, null,
                request -> new CompactionStrategy("把历史压成摘要即可", null, null), RegisterOptions.DEFAULT);

        CompactionPlan plan = compactor.plan(createdSessionId);

        assertEquals("把历史压成摘要即可", plan.getRequest().getSystemPrompt());
    }

    @Test
    @DisplayName("压缩成功后：边界推进、摘要落库、状态变 DONE、用量计入会话")
    void start_should_applyCompaction_when_modelReturnsSummary() throws Exception {
        Session session = sessionWithMessages(6);
        givenModel(128_000, 4_000);
        when(client.chat(any(LlmRequest.class))).thenReturn(new LlmResponse("压出来的摘要",
                null, Collections.emptyList(), new LlmUsage(100, 20, 120), "stop"));

        compactor.start(createdSessionId, CompactionTrigger.MANUAL);

        awaitIdle();
        ConversationCompactor.State state = compactor.status(createdSessionId);
        assertEquals(ConversationCompactor.Status.DONE, state.getStatus());
        assertEquals(CompactionTrigger.MANUAL, state.getTrigger());
        assertNotNull(session.getCompaction());
        assertEquals("压出来的摘要", session.getCompaction().getSummary());
        // 6 条消息、保留最近 3 条 → 边界落在第 3 条，没有任何消息被丢弃
        assertEquals(boundaryIdOf(session, 2), session.getCompaction().getBoundaryMessageId());
        assertEquals(0, session.getCompaction().getDroppedMessageCount());
        assertEquals(120L, session.getUsage().getTotalTokens());
    }

    @Test
    @DisplayName("摘要请求带上旧摘要与新历史两块：不合并旧摘要就会永久丢信息")
    void start_should_feedPreviousSummaryIntoRequest() {
        Session session = sessionWithMessages(8);
        // 已压过前 3 条：新一次压缩只能从第 4 条开始，且必须带上旧摘要
        sessionManager.applyCompaction(createdSessionId, "上一份摘要", boundaryIdOf(session, 2), 0);
        givenModel(128_000, 4_000);

        CompactionPlan plan = compactor.plan(createdSessionId);

        String body = plan.getRequest().getMessages().get(0).getContent();
        assertTrue(body.contains("上一份摘要"), body);
        assertTrue(body.contains("新增对话历史"), body);
        // 只压旧边界之后的部分：前 3 条已被上一份摘要覆盖，不该再花一次钱
        assertFalse(body.contains("第 1 条"), body);
        assertTrue(body.contains("第 4 条"), body);
    }

    @Test
    @DisplayName("滚动摘要：已压过的那一段不再进摘要输入，边界只会向后移")
    void plan_should_startAfterPreviousBoundary() {
        Session session = sessionWithMessages(8);
        sessionManager.applyCompaction(createdSessionId, "旧摘要", boundaryIdOf(session, 2), 0);
        givenModel(128_000, 4_000);

        // 保留最近 5 条 → 待压范围只剩 0 条（8 条里压了 3 条、其余 5 条要保留）
        applyKeepRecent(5);
        assertNull(compactor.plan(createdSessionId));

        // 保留最近 3 条 → 从第 4 条压到第 5 条，边界落在第 5 条
        applyKeepRecent(3);
        CompactionPlan plan = compactor.plan(createdSessionId);
        assertEquals(2, plan.getCompressedCount());
        assertEquals(boundaryIdOf(session, 4), plan.getBoundaryMessageId());
    }

    @Test
    @DisplayName("保留条数恰好等于全部消息时没有可压内容，start 同步报错")
    void start_should_fail_when_nothingToCompress() {
        sessionWithMessages(3);
        givenModel(128_000, 4_000);

        // 保留最近 3 条 → 待压 0 条
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> compactor.start(createdSessionId, CompactionTrigger.MANUAL));

        assertEquals("没有足够的历史可压缩", error.getMessage());
    }

    @Test
    @DisplayName("保留 0 条：边界落在最后一条消息上，待压范围是整段历史")
    void plan_should_compressEverything_when_keepRecentZero() {
        Session session = sessionWithMessages(4);
        givenModel(128_000, 4_000);
        applyKeepRecent(0);

        CompactionPlan plan = compactor.plan(createdSessionId);

        assertEquals(4, plan.getCompressedCount());
        assertEquals(0, plan.getKeepCount());
        assertEquals(session.getMessages().get(3).getMessageId(), plan.getBoundaryMessageId());
    }

    @Test
    @DisplayName("模型异常时状态为 FAILED 且会话一字未改")
    void start_should_keepSessionUntouched_when_modelFails() throws Exception {
        Session session = sessionWithMessages(6);
        givenModel(128_000, 4_000);
        when(client.chat(any(LlmRequest.class))).thenThrow(new JellyfishException("供应商 500"));

        compactor.start(createdSessionId, CompactionTrigger.MANUAL);

        awaitIdle();
        assertEquals(ConversationCompactor.Status.FAILED, compactor.status(createdSessionId).getStatus());
        assertTrue(compactor.status(createdSessionId).getMessage().contains("供应商 500"));
        assertNull(session.getCompaction());
    }

    @Test
    @DisplayName("摘要为空白算失败：推进边界却没摘要等于把那段历史凭空删掉")
    void start_should_fail_when_summaryBlank() throws Exception {
        Session session = sessionWithMessages(6);
        givenModel(128_000, 4_000);
        when(client.chat(any(LlmRequest.class))).thenReturn(LlmResponse.text("   "));

        compactor.start(createdSessionId, CompactionTrigger.MANUAL);

        awaitIdle();
        assertEquals(ConversationCompactor.Status.FAILED, compactor.status(createdSessionId).getStatus());
        assertEquals("模型没有返回摘要", compactor.status(createdSessionId).getMessage());
        assertNull(session.getCompaction());
    }

    @Test
    @DisplayName("摘要为空白时用量仍要记账：请求已经发出去了，token 就花掉了")
    void start_should_recordUsage_even_when_summaryBlank() throws Exception {
        Session session = sessionWithMessages(6);
        givenModel(128_000, 4_000);
        when(client.chat(any(LlmRequest.class))).thenReturn(new LlmResponse("",
                null, Collections.emptyList(), new LlmUsage(50, 0, 50), "stop"));

        compactor.start(createdSessionId, CompactionTrigger.MANUAL);

        awaitIdle();
        assertEquals(50L, session.getUsage().getTotalTokens());
    }

    @Test
    @DisplayName("摘要超长按码点截断并留标记，且不会在代理对中间切开")
    void start_should_truncateOversizedSummary_byCodePoint() throws Exception {
        Session session = sessionWithMessages(6);
        givenModel(128_000, 4_000);
        // 摘要上限压到 3 个字符：先 givenModel 再覆盖，否则会被它的缺省桩盖回去
        when(runtimeConfig.getReactSettings())
                .thenReturn(new ReactSettings(16, 1024, 20000, 3, 3, 80));
        when(client.chat(any(LlmRequest.class))).thenReturn(LlmResponse.text("汉汉汉\uD83D\uDE00"));

        compactor.start(createdSessionId, CompactionTrigger.MANUAL);

        awaitIdle();
        String summary = session.getCompaction().getSummary();
        assertTrue(summary.startsWith("汉汉汉"), summary);
        assertTrue(summary.endsWith("（摘要超长，已截断）"), summary);
    }

    @Test
    @DisplayName("并发重复启动只放行一个：第二个当场报「压缩已在进行中」")
    void start_should_rejectSecondRun_when_running() throws Exception {
        sessionWithMessages(6);
        givenModel(128_000, 4_000);
        // 用闩锁把第一次压缩卡在模型调用里：不用 sleep，第一个压缩的 RUNNING 是确定性的
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(client.chat(any(LlmRequest.class))).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return LlmResponse.text("摘要");
        });

        compactor.start(createdSessionId, CompactionTrigger.MANUAL);
        assertTrue(entered.await(5, TimeUnit.SECONDS), "第一次压缩没有开始");
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> compactor.start(createdSessionId, CompactionTrigger.MANUAL));

        assertEquals("压缩已在进行中，请等待当前压缩结束。", error.getMessage());
        assertEquals(ConversationCompactor.Status.RUNNING, compactor.status(createdSessionId).getStatus());
        release.countDown();
        awaitIdle();
    }

    @Test
    @DisplayName("执行器拒绝任务时状态不能卡在 RUNNING：否则该会话之后永远压不了")
    void start_should_resetState_when_executorRejects() {
        sessionWithMessages(6);
        givenModel(128_000, 4_000);
        ExecutorService rejecting = mock(ExecutorService.class);
        doThrow(new RejectedExecutionException("queue full"))
                .when(rejecting).execute(any(Runnable.class));
        ConversationCompactor failing = newCompactor(rejecting);

        JellyfishException error = assertThrows(JellyfishException.class,
                () -> failing.start(createdSessionId, CompactionTrigger.MANUAL));

        assertEquals("压缩任务队列已满，请稍后重试", error.getMessage());
        ConversationCompactor.State state = failing.status(createdSessionId);
        assertEquals(ConversationCompactor.Status.FAILED, state.getStatus());
        assertFalse(state.isRunning(), "状态卡在 RUNNING 会让这个会话永远无法再压缩");
    }

    @Test
    @DisplayName("预算装不下整段待压历史时丢弃最旧的一段：只压最新的一部分，边界仍然推进到底")
    void plan_should_dropOldestMessages_when_budgetLimited() {
        Session session = sessionWithMessages(10);
        // 窗口小到只装得下两三条
        givenModel(200, 50);

        CompactionPlan plan = compactor.plan(createdSessionId);

        assertNotNull(plan);
        assertTrue(plan.hasDropped(), "预算不足时应丢弃最旧的一段");
        // 保留最近 3 条 → 待压范围是第 1~7 条；进摘要的只是其中最新的一小截
        assertTrue(plan.getCompressedCount() < 7, "压的条数应少于待压范围");
        assertEquals(7 - plan.getCompressedCount(), plan.getDroppedCount());
        // 边界仍然落在待压范围的最后一条（第 7 条）：丢弃不等于少推进
        assertEquals(boundaryIdOf(session, 6), plan.getBoundaryMessageId());
        String body = plan.getRequest().getMessages().get(0).getContent();
        assertFalse(body.contains("第 1 条"), body);
        assertTrue(body.contains("第 7 条"), body);
    }

    @Test
    @DisplayName("预算够时一条都不丢：整段待压范围都进摘要")
    void plan_should_notDrop_when_budgetEnough() {
        sessionWithMessages(10);
        givenModel(128_000, 4_000);

        CompactionPlan plan = compactor.plan(createdSessionId);

        assertFalse(plan.hasDropped());
        assertEquals(7, plan.getCompressedCount());
        String body = plan.getRequest().getMessages().get(0).getContent();
        assertTrue(body.contains("第 1 条"), body);
    }

    @Test
    @DisplayName("模型未配上下文窗口时不设上限：把整段待压历史一次压完")
    void plan_should_notLimit_when_contextLengthMissing() {
        sessionWithMessages(10);
        givenModel(0, 0);

        CompactionPlan plan = compactor.plan(createdSessionId);

        assertEquals(7, plan.getCompressedCount());
        assertFalse(plan.hasDropped());
    }

    @Test
    @DisplayName("边界消息不在会话里时按未压缩处理：不猜位置，也不注入一段无人认领的摘要")
    void plan_should_fallBackToFullHistory_when_boundaryMissing() {
        // 唯一能让「压缩记录指向不存在的消息」出现的路径是恢复一份手工改过的会话文件，
        // 因此这里从恢复处理器喂一份边界消息不存在的快照，而不是绕过 SessionManager 直接改字段
        extensions = restoringExtensions("m-ghost");
        // 兜底策略要跟着换注册表：本用例关心的是边界失效的回退，不是「有没有插件」
        extensions.contribute("fallback", CompactionStrategyRequest.class, null,
                request -> new CompactionStrategy(FALLBACK_PROMPT, null, null), RegisterOptions.order(100));
        sessionManager = new SessionManager(agentManager, events, extensions, new SessionDefaults());
        executor = Executors.newSingleThreadExecutor();
        compactor = newCompactor(executor);
        sessionManager.restore();
        Session session = sessionManager.require(RESTORED_SESSION_ID);
        assertEquals("孤儿摘要", session.getCompaction().getSummary());
        givenModel(128_000, 4_000);
        applyKeepRecent(3);

        CompactionPlan plan = compactor.plan(RESTORED_SESSION_ID);

        // 找不到边界 → 按从未压缩过处理：整段历史重新进摘要输入
        assertEquals(1, plan.getCompressedCount());
        String body = plan.getRequest().getMessages().get(0).getContent();
        assertTrue(body.contains("第 1 条"), body);
        // 那段无人认领的摘要也不能喂给模型：它覆盖的是哪一段已无从判断
        assertFalse(body.contains("孤儿摘要"), body);
    }

    @Test
    @DisplayName("没有会话时直接报错，不产生任何状态")
    void plan_should_fail_when_sessionMissing() {
        assertThrows(JellyfishException.class, () -> compactor.plan("ghost"));
    }

    @Test
    @DisplayName("压缩跑在别的线程上：start 立即返回，不阻塞调用点")
    void start_should_returnImmediately() throws Exception {
        sessionWithMessages(6);
        givenModel(128_000, 4_000);
        // 让模型调用一直挂着：若 start 在调用点等待，下面那条断言必然超时
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(client.chat(any(LlmRequest.class))).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return LlmResponse.text("摘要");
        });

        long start = System.nanoTime();
        compactor.start(createdSessionId, CompactionTrigger.MANUAL);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMillis < 1000L, "start 耗时 " + elapsedMillis + "ms，说明它在调用点等待了模型");
        assertTrue(entered.await(5, TimeUnit.SECONDS), "压缩任务没有开始");
        release.countDown();
        awaitIdle();
    }

    @Test
    @DisplayName("从未压过的会话状态是 IDLE，未知会话也是 IDLE")
    void status_should_beIdle_when_neverCompacted() {
        assertEquals(ConversationCompactor.Status.IDLE, compactor.status("no-such-session").getStatus());
        assertEquals(ConversationCompactor.Status.IDLE, compactor.status(null).getStatus());
    }

    @Test
    @DisplayName("摘要输入按角色与工具名标注：摘要要能分清「谁说的」和「工具看到了什么」")
    void plan_should_labelRolesAndToolCalls() {
        Session session = sessionManager.createDefault();
        sessionManager.switchTo(session.getSessionId());
        sessionManager.appendMessage(session.getSessionId(), LlmMessage.user("看看 a.txt"), null);
        sessionManager.appendMessage(session.getSessionId(),
                LlmMessage.assistant("我读一下", Collections.singletonList(
                        new LlmToolCall(0, "c-1", "read_file", "{\"path\":\"a.txt\"}"))),
                null);
        sessionManager.appendMessage(session.getSessionId(),
                LlmMessage.tool("c-1", "read_file", "文件内容"), null);
        givenModel(128_000, 4_000);
        applyKeepRecent(0);

        CompactionPlan plan = compactor.plan(session.getSessionId());

        String body = plan.getRequest().getMessages().get(0).getContent();
        assertTrue(body.contains("[用户]"), body);
        assertTrue(body.contains("[助手]"), body);
        assertTrue(body.contains("[工具调用] read_file({\"path\":\"a.txt\"})"), body);
        assertTrue(body.contains("[工具结果 read_file]"), body);
    }

    @Test
    @DisplayName("用量未到阈值不自动压：空转不该花用户的钱")
    void autoCompactIfNeeded_should_skip_when_belowThreshold() {
        sessionWithMessages(6);
        // 只桩配置：未到阈值就该在碰模型之前返回，模型侧连桩都不需要
        applyKeepRecent(3);

        boolean started = compactor.autoCompactIfNeeded(createdSessionId, new ContextUsage(10, 100, false));

        assertFalse(started);
        verify(client, never()).chat(any(LlmRequest.class));
        assertEquals(ConversationCompactor.Status.IDLE, compactor.status(createdSessionId).getStatus());
    }

    @Test
    @DisplayName("用量到阈值就自动压一次，并标出来源是自动")
    void autoCompactIfNeeded_should_start_when_aboveThreshold() throws Exception {
        Session session = sessionWithMessages(6);
        givenModel(128_000, 4_000);
        when(client.chat(any(LlmRequest.class))).thenReturn(LlmResponse.text("自动压出来的摘要"));

        boolean started = compactor.autoCompactIfNeeded(createdSessionId, new ContextUsage(80, 100, false));

        assertTrue(started);
        awaitIdle();
        assertEquals("自动压出来的摘要", session.getCompaction().getSummary());
        assertEquals(CompactionTrigger.AUTO, compactor.status(createdSessionId).getTrigger());
    }

    @Test
    @DisplayName("本次请求已被机械裁剪时也要压：历史正在丢，比到 80% 更该压")
    void autoCompactIfNeeded_should_start_when_requestTruncated() throws Exception {
        sessionWithMessages(6);
        givenModel(128_000, 4_000);
        when(client.chat(any(LlmRequest.class))).thenReturn(LlmResponse.text("摘要"));

        boolean started = compactor.autoCompactIfNeeded(createdSessionId, new ContextUsage(10, 100, true));

        assertTrue(started);
        awaitIdle();
        assertEquals(CompactionTrigger.AUTO, compactor.status(createdSessionId).getTrigger());
    }

    @Test
    @DisplayName("百分比配 0 即关闭自动压缩：只留手动 /compact")
    void autoCompactIfNeeded_should_beDisabled_when_percentZero() {
        sessionWithMessages(6);
        when(runtimeConfig.getReactSettings())
                .thenReturn(new ReactSettings(16, 1024, 20000, 3, 4000, 0));

        boolean started = compactor.autoCompactIfNeeded(createdSessionId, new ContextUsage(99, 100, true));

        assertFalse(started);
        verify(client, never()).chat(any(LlmRequest.class));
    }

    @Test
    @DisplayName("没有可压历史时自动压缩静默返回：不发请求、不报错")
    void autoCompactIfNeeded_should_returnFalse_when_nothingToCompress() {
        sessionWithMessages(3);
        givenModel(128_000, 4_000);

        boolean started = compactor.autoCompactIfNeeded(createdSessionId, new ContextUsage(99, 100, true));

        assertFalse(started);
        verify(client, never()).chat(any(LlmRequest.class));
    }

    @Test
    @DisplayName("已有压缩在跑时自动压缩让路：不排队、不叠加")
    void autoCompactIfNeeded_should_returnFalse_when_alreadyRunning() throws Exception {
        sessionWithMessages(6);
        givenModel(128_000, 4_000);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(client.chat(any(LlmRequest.class))).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return LlmResponse.text("摘要");
        });
        compactor.start(createdSessionId, CompactionTrigger.MANUAL);
        assertTrue(entered.await(5, TimeUnit.SECONDS), "第一次压缩没有开始");

        boolean started = compactor.autoCompactIfNeeded(createdSessionId, new ContextUsage(99, 100, true));

        assertFalse(started);
        release.countDown();
        awaitIdle();
    }

    @Test
    @DisplayName("插件给的摘要指令成为 system prompt，占位符被替换成它给的上限")
    void plan_should_usePluginStrategy() {
        sessionWithMessages(6);
        givenModel(128_000, 4_000);
        registerStrategy(request -> new CompactionStrategy(
                "本次请额外保留报错原文，不超过 {maxSummaryChars} 字", 3, 1234));

        CompactionPlan plan = compactor.plan(createdSessionId);

        assertEquals(3, plan.getKeepCount());
        String prompt = plan.getRequest().getSystemPrompt();
        assertTrue(prompt.contains("本次请额外保留报错原文"), prompt);
        // 占位符必须被换掉：留着它，模型会看到一个字面量花括号
        assertTrue(prompt.contains("1234"), prompt);
        assertFalse(prompt.contains(CompactionStrategy.MAX_CHARS_PLACEHOLDER), prompt);
    }

    @Test
    @DisplayName("多个插件按 order 逐字段取第一：指令不拼接，数值也不取极值")
    void plan_should_mergeStrategiesByOrder() {
        sessionWithMessages(6);
        givenModel(128_000, 4_000);
        // first 只给指令、second 只给数值：两份策略合起来才是完整的一次压缩
        extensions.contribute("first", CompactionStrategyRequest.class, null,
                request -> new CompactionStrategy("第一段指令", null, null), RegisterOptions.DEFAULT);
        extensions.contribute("second", CompactionStrategyRequest.class, null,
                request -> new CompactionStrategy(null, 2, 1234), RegisterOptions.DEFAULT);

        CompactionPlan plan = compactor.plan(createdSessionId);

        assertEquals(2, plan.getKeepCount());
        String prompt = plan.getRequest().getSystemPrompt();
        assertEquals("第一段指令", prompt);
        assertFalse(prompt.contains("第二段"), prompt);
    }

    @Test
    @DisplayName("指令取 order 最小者：后来的插件不会把它顶掉")
    void plan_should_keepFirstPrompt_when_laterPluginAlsoGivesOne() {
        sessionWithMessages(6);
        givenModel(128_000, 4_000);
        extensions.contribute("first", CompactionStrategyRequest.class, null,
                request -> new CompactionStrategy("高优先级指令", null, null), RegisterOptions.DEFAULT);
        extensions.contribute("second", CompactionStrategyRequest.class, null,
                request -> new CompactionStrategy("低优先级指令", null, null), RegisterOptions.DEFAULT);

        CompactionPlan plan = compactor.plan(createdSessionId);

        assertEquals("高优先级指令", plan.getRequest().getSystemPrompt());
    }

    @Test
    @DisplayName("插件给的越界参数会被钳制：荒谬的保留条数不该让压缩失控")
    void plan_should_clampPluginNumbers() {
        sessionWithMessages(6);
        givenModel(128_000, 4_000);
        registerStrategy(request -> new CompactionStrategy("不超过 {maxSummaryChars} 字", -5, 1));

        CompactionPlan plan = compactor.plan(createdSessionId);

        // 负数保留条数 → 0（全压）；摘要上限 1 → 抬到下限，否则摘要会被截成一句空话
        assertEquals(0, plan.getKeepCount());
        assertTrue(plan.getRequest().getSystemPrompt()
                .contains(String.valueOf(ReactSettings.MIN_COMPACT_MAX_SUMMARY_CHARS)));
    }

    @Test
    @DisplayName("插件策略处理器抛错只跳过它自己：兜底策略仍让压缩可用")
    void plan_should_skipPlugin_when_handlerFails() {
        sessionWithMessages(6);
        givenModel(128_000, 4_000);
        registerStrategy(request -> {
            throw new IllegalStateException("插件崩了");
        });

        CompactionPlan plan = compactor.plan(createdSessionId);

        assertEquals(3, plan.getKeepCount());
        assertEquals(FALLBACK_PROMPT.replace("{maxSummaryChars}", "4000"),
                plan.getRequest().getSystemPrompt());
    }

    @Test
    @DisplayName("策略请求只带数字与标识，不带任何一条消息正文")
    void plan_should_notLeakMessageContent_toPlugin() {
        sessionWithMessages(6);
        givenModel(128_000, 4_000);
        List<CompactionStrategyRequest> seen = new ArrayList<CompactionStrategyRequest>();
        registerStrategy(request -> {
            seen.add(request);
            return CompactionStrategy.none();
        });

        compactor.plan(createdSessionId);

        assertEquals(1, seen.size());
        CompactionStrategyRequest request = seen.get(0);
        assertEquals(CompactionTrigger.MANUAL, request.getTrigger());
        assertEquals(6, request.getMessageCount());
        assertEquals(0, request.getCompressedCount());
        assertEquals(3, request.getDefaultKeepRecentMessages());
        assertEquals(4000, request.getDefaultMaxSummaryChars());
        assertEquals("gpt-4o", request.getModelId());
        assertTrue(request.getBudgetTokens() > 0);
    }

    /**
     * 构造压缩器（真实提示词资源 + 真实扩展点策略）。
     *
     * @param taskExecutor 压缩任务的执行器
     * @return 压缩器
     */
    private ConversationCompactor newCompactor(ExecutorService taskExecutor) {
        return new ConversationCompactor(sessionManager, modelManager, runtimeConfig, extensions,
                events, new SessionModelResolver(modelManager, agentManager), taskExecutor);
    }

    /**
     * 注册一个压缩策略处理器。
     *
     * @param handler 处理器
     */
    private void registerStrategy(
            zcd.jellyfish.api.extension.ExtensionHandler<CompactionStrategyRequest, CompactionStrategy> handler) {
        extensions.contribute("test-strategy", CompactionStrategyRequest.class, null, handler,
                RegisterOptions.DEFAULT);
    }

    /**
     * 只改「保留条数」的配置桩：其余参数用缺省值。
     *
     * @param keepRecent 保留的最近消息条数
     */
    private void applyKeepRecent(int keepRecent) {
        lenient().when(runtimeConfig.getReactSettings())
                .thenReturn(new ReactSettings(16, 1024, 20000, keepRecent, 4000, 80));
    }

    /**
     * 构造一条带 N 条消息的会话。
     * <p>
     * 消息正文带上序号，便于断言「哪一条进了摘要输入」。
     *
     * @param count 消息条数
     * @return 会话运行态
     */
    private Session sessionWithMessages(int count) {
        Session session = sessionManager.createDefault();
        sessionManager.switchTo(session.getSessionId());
        createdSessionId = session.getSessionId();
        for (int index = 1; index <= count; index++) {
            sessionManager.appendMessage(session.getSessionId(), LlmMessage.user("第 " + index + " 条"), null);
        }
        return session;
    }

    /**
     * 桩上模型解析链路：会话未绑定 provider / model，因此走默认。
     *
     * @param contextLength   上下文窗口
     * @param maxOutputTokens 最大输出
     */
    private void givenModel(int contextLength, int maxOutputTokens) {
        // 用真实 Model 而不是 mock：它只是个不可变值对象，mock 它反而要逐个字段打桩，
        // 且「哪些桩被用到了」会随执行路径变化，Mockito 的多余打桩检查会把测试变得很脆
        Model model = new Model("gpt-4o", "gpt-4o", contextLength, maxOutputTokens);
        ResolvedModel resolved = new ResolvedModel(mock(Provider.class), model);
        when(modelManager.resolveDefault()).thenReturn(resolved);
        // 只出计划（preview）的用例不会发请求，因此这里用 lenient 免得被「多余打桩」判失败
        lenient().when(modelManager.getClient(resolved)).thenReturn(client);
        applyKeepRecent(3);
    }

    /**
     * 等待异步压缩收敛。
     *
     * @throws Exception 等待被中断时抛出
     */
    private void awaitIdle() throws Exception {
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "压缩任务没有在预期时间内结束");
    }

    /**
     * 取会话里第 index 条消息的标识（压缩边界以消息标识记账）。
     *
     * @param session 会话
     * @param index   下标（从 0 起）
     * @return 消息标识
     */
    private static String boundaryIdOf(Session session, int index) {
        return session.getMessages().get(index).getMessageId();
    }

    /**
     * 构造一个不接任何处理器的同步扩展点策略：会话落盘在本测试里不需要插件。
     *
     * @return 扩展点策略
     */
    private static ExtensionRegistry newRecordingExtensions() {
        return new ExtensionRegistry(new TypeRegistry());
    }

    /**
     * 构造一个会「恢复出」一份指定会话的扩展点策略。
     * <p>
     * 用于构造「压缩边界指向一条不存在的消息」这种只可能来自手工改过会话文件的状态——
     * 正常路径下 {@code SessionManager.applyCompaction} 会拦住它。
     *
     * @param boundaryMessageId 快照里那条不存在的边界消息标识
     * @return 扩展点策略
     */
    private static ExtensionRegistry restoringExtensions(String boundaryMessageId) {
        ExtensionRegistry registry = newRecordingExtensions();
        SessionSnapshot snapshot = new SessionSnapshot(RESTORED_SESSION_ID, 1L, 2L, null, null, null, null,
                PermissionMode.NORMAL, messagesOf(4), null,
                SessionCompactionSnapshot.of("孤儿摘要", boundaryMessageId, 3L));
        registry.contribute("restorer", SessionRestoreRequest.class, null,
                request -> SessionRestoreResult.of(Collections.singletonList(snapshot)),
                RegisterOptions.DEFAULT);
        return registry;
    }

    /**
     * 构造一批带序号的消息快照。
     *
     * @param count 条数
     * @return 消息快照列表
     */
    private static List<SessionMessageSnapshot> messagesOf(int count) {
        List<SessionMessageSnapshot> messages = new ArrayList<SessionMessageSnapshot>(count);
        for (int index = 1; index <= count; index++) {
            messages.add(SessionMessageSnapshot.of("restored-" + index, index, LlmMessage.ROLE_USER,
                    "第 " + index + " 条", null, null, null, null));
        }
        return messages;
    }
}
