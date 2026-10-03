package zcd.jellyfish.core.subagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.extension.CancellationToken;
import zcd.jellyfish.api.subagent.DelegationHandle;
import zcd.jellyfish.api.subagent.DelegationRequest;
import zcd.jellyfish.api.subagent.DelegationResult;
import zcd.jellyfish.api.subagent.DelegationStatus;
import zcd.jellyfish.infra.session.SessionUsage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SubAgentDelegationAdapter} 的单元测试：请求与结果的翻译。
 * <p>
 * 委派器本身用 mock——本类的职责只有「把 api 类型翻成内核类型、再把内核结果翻回 api 类型」，
 * 准入、并发、收尾那些行为属于 {@code SubAgentLauncher}，已在
 * {@code SubAgentLauncherTest} 里逐条锁住。这里再起一遍真实的会话与调度只是重复。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class SubAgentDelegationAdapterTest {

    /** 子代理委派器。 */
    @Mock
    private SubAgentLauncher launcher;

    /** 内核句柄。 */
    @Mock
    private SubAgentRunHandle runHandle;

    /** 被测适配器。 */
    private SubAgentDelegationAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SubAgentDelegationAdapter(launcher);
    }

    @Test
    void spawn_should_translate_request_and_not_forward_progress() {
        // Given
        CancellationToken token = new CancellationToken() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void onCancel(Runnable callback) {
                // 本用例不观察取消
            }
        };
        when(launcher.spawn(any(SubAgentCall.class), any())).thenReturn(runHandle);
        when(runHandle.getRunId()).thenReturn("run-1");

        // When
        DelegationHandle handle = adapter.spawn(
                new DelegationRequest("s-1", "scout", "查一下", token));

        // Then：请求逐字段搬运；进度回调传 null（ReActListener 是内核类型，插件看不到）
        ArgumentCaptor<SubAgentCall> captor = ArgumentCaptor.forClass(SubAgentCall.class);
        verify(launcher).spawn(captor.capture(), isNull());
        SubAgentCall call = captor.getValue();
        assertEquals("s-1", call.getParentSessionId());
        assertEquals("scout", call.getAgentId());
        assertEquals("查一下", call.getPrompt());
        assertSame(token, call.getCancellationToken());
        assertEquals("run-1", handle.runId());
    }

    @Test
    void await_should_translate_outcome_to_api_result() {
        // Given：子代理完成，花了 30 token
        when(launcher.spawn(any(SubAgentCall.class), isNull())).thenReturn(runHandle);
        when(runHandle.getRunId()).thenReturn("run-1");
        when(launcher.await(runHandle)).thenReturn(
                SubAgentOutcome.completed("结论", 3, new SessionUsage(10L, 20L, 30L, 2L)));

        // When
        DelegationHandle handle = adapter.spawn(DelegationRequest.of("s-1", "scout", "查一下"));
        DelegationResult result = handle.await();

        // Then
        assertEquals(DelegationStatus.COMPLETED, result.getStatus());
        assertEquals("run-1", result.getRunId());
        assertEquals("结论", result.getText());
        assertEquals(3, result.getRounds());
        assertEquals(30L, result.getTotalTokens());
        // 幂等：重复等待返回同一个结果，不会触发第二次收尾
        assertSame(result, handle.await());
    }

    @Test
    void await_should_map_rejection_without_run_id() {
        // Given：被拒 = 从未开始，内核句柄里没有 runId
        when(launcher.spawn(any(SubAgentCall.class), isNull())).thenReturn(runHandle);
        when(launcher.await(runHandle))
                .thenReturn(SubAgentOutcome.rejected("子代理委派已被禁用（jellyfish.json 的 subAgent.enabled）"));

        // When
        DelegationResult result = adapter.spawn(DelegationRequest.of("s-1", "scout", "查一下")).await();

        // Then：REJECTED 而不是 FAILED —— 编排方据此判断「重试同一个请求没有意义」
        assertEquals(DelegationStatus.REJECTED, result.getStatus());
        assertNull(result.getRunId());
        assertTrue(result.getError().contains("已被禁用"));
    }

    @Test
    void await_should_map_truncated_and_cancelled() {
        when(launcher.spawn(any(SubAgentCall.class), isNull())).thenReturn(runHandle);
        when(runHandle.getRunId()).thenReturn("run-1");
        when(launcher.await(runHandle)).thenReturn(
                SubAgentOutcome.truncated("已达轮数上限", 8, SessionUsage.EMPTY),
                SubAgentOutcome.cancelled(2, SessionUsage.EMPTY));

        DelegationResult truncated = adapter.spawn(DelegationRequest.of("s-1", "scout", "a")).await();
        DelegationResult cancelled = adapter.spawn(DelegationRequest.of("s-1", "scout", "b")).await();

        assertEquals(DelegationStatus.TRUNCATED, truncated.getStatus());
        assertEquals("已达轮数上限", truncated.getText());
        assertEquals(DelegationStatus.CANCELLED, cancelled.getStatus());
        assertEquals(2, cancelled.getRounds());
    }

    @Test
    void cancel_should_delegate_to_the_kernel_handle() {
        when(launcher.spawn(any(SubAgentCall.class), isNull())).thenReturn(runHandle);

        adapter.spawn(DelegationRequest.of("s-1", "scout", "查一下")).cancel();

        verify(runHandle).cancel();
    }
}
