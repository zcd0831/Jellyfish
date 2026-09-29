package zcd.jellyfish.core.subagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.extension.ToolMetadata;
import zcd.jellyfish.api.extension.ToolOutputSink;
import zcd.jellyfish.core.ReActListener;
import zcd.jellyfish.infra.session.SessionUsage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TaskTool} 的单元测试：验证参数翻译、结果渲染（首行结论）、元数据约定与进度旁路。
 * <p>
 * 委派器用 mock：本类只负责「把内核与委派器接起来」，准入与执行编排在
 * {@code SubAgentLauncherTest} 里验证。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class TaskToolTest {

    /** 子代理委派器。 */
    @Mock
    private SubAgentLauncher launcher;

    /** 被测对象。 */
    private TaskTool tool;

    /** 录制型输出通道。 */
    private RecordingSink sink;

    @BeforeEach
    void setUp() {
        tool = new TaskTool(launcher);
        sink = new RecordingSink();
    }

    @Test
    void descriptor_should_declare_required_arguments() {
        // When
        ToolDescriptor descriptor = tool.descriptor();

        // Then
        assertEquals(TaskTool.NAME, descriptor.getName());
        assertEquals(2, descriptor.getRequired().size());
        assertEquals("subagent_type", descriptor.getRequired().get(0));
        assertEquals("prompt", descriptor.getRequired().get(1));
        assertTrue(descriptor.getParameters().containsKey("prompt"));
        // 它会改外部状态（派生模型调用），不能声明成只读
        assertFalse(descriptor.isReadOnly());
    }

    @Test
    void handle_should_return_missingArgument_when_subagent_type_blank() {
        // When
        ToolCallResult result = tool.handle(request("   ", "查一下"));

        // Then：参数缺失是 schema 层面的问题，不该惊动委派器
        assertTrue(result.getOutput().toString().startsWith("[子代理未开始]"));
        assertTrue(result.getOutput().toString().contains("subagent_type"));
        assertTrue(sink.chunks.isEmpty());
    }

    @Test
    void handle_should_pass_call_and_listener_to_launcher() {
        // Given
        when(launcher.run(any(SubAgentCall.class), any(ReActListener.class)))
                .thenReturn(SubAgentOutcome.completed("结论", 2, SessionUsage.EMPTY));
        ArgumentCaptor<SubAgentCall> callCaptor = ArgumentCaptor.forClass(SubAgentCall.class);

        // When
        tool.handle(request("scout", "查一下登录流程"));

        // Then
        verifyCall(callCaptor);
        SubAgentCall call = callCaptor.getValue();
        assertEquals("scout", call.getAgentId());
        assertEquals("查一下登录流程", call.getPrompt());
    }

    @Test
    void handle_should_render_header_then_text_when_completed() {
        // Given
        when(launcher.run(any(SubAgentCall.class), any(ReActListener.class)))
                .thenReturn(SubAgentOutcome.completed("登录走 OAuth2", 3, usage(42L)));

        // When
        ToolCallResult result = tool.handle(request("scout", "查一下登录流程"));

        // Then：首行写结论——模型不必读完整个报告才知道这次委派成没成
        String output = result.getOutput().toString();
        assertTrue(output.startsWith("[子代理 scout 已完成 · 3 轮]"), output);
        assertTrue(output.endsWith("登录走 OAuth2"), output);
        assertEquals("scout", result.getMetadata().get("subagent"));
        assertEquals("completed", result.getMetadata().get("status"));
        assertEquals(3, result.getMetadata().get("rounds"));
        assertEquals(42L, result.getMetadata().get("totalTokens"));
        assertNull(result.getMetadata().get(ToolMetadata.KEY_TERMINAL));
        assertFalse(ToolMetadata.failed(result.getMetadata()));
        // 轨迹行上的摘要：「谁 · 跑了几轮 · 花了多少」
        assertEquals("子代理 scout · 3 轮 · 42 tok", result.getMetadata().get(ToolMetadata.KEY_SUMMARY));
    }

    @Test
    void handle_should_write_token_count_in_summary() {
        // Given
        when(launcher.run(any(SubAgentCall.class), any(ReActListener.class)))
                .thenReturn(SubAgentOutcome.completed("结论", 3, usage(123456L)));

        // When
        String summary = summaryOf(tool.handle(request("scout", "查一下")));

        // Then：精确值，可与 /usage 里的数字直接对上
        assertEquals("子代理 scout · 3 轮 · 123456 tok", summary);
    }

    @Test
    void handle_should_omit_tokens_in_summary_when_zero() {
        // Given：未开始与直接失败按构造就没有用量
        when(launcher.run(any(SubAgentCall.class), any(ReActListener.class)))
                .thenReturn(SubAgentOutcome.completed("结论", 2, SessionUsage.EMPTY));

        // When / Then：写「0 tok」会让人以为它跑过但没花额度
        assertEquals("子代理 scout · 2 轮", summaryOf(tool.handle(request("scout", "查一下"))));
    }

    @Test
    void handle_should_mark_incomplete_summary_when_truncated() {
        // Given
        when(launcher.run(any(SubAgentCall.class), any(ReActListener.class)))
                .thenReturn(SubAgentOutcome.truncated("只查了一半", 8, usage(10L)));

        // When
        ToolCallResult result = tool.handle(request("scout", "查一下"));

        // Then：达到轮数上限是唯一一种「没跑好、却不算异常终止」的情形，没有警示后缀可用
        assertFalse(ToolMetadata.failed(result.getMetadata()), "截断不算异常终止");
        assertEquals("子代理 scout · 8 轮 · 10 tok · 结论不完整", summaryOf(result));
    }

    @Test
    void handle_should_omit_rounds_and_tokens_in_summary_when_never_ran() {
        // Given：拒绝与失败按构造轮数与用量都是 0，写「0 轮」会读成「跑了但没跑出东西」
        when(launcher.run(any(SubAgentCall.class), any(ReActListener.class)))
                .thenReturn(SubAgentOutcome.rejected("未知的子代理类型：ghost"));

        // When / Then
        assertEquals("子代理 ghost", summaryOf(tool.handle(request("ghost", "查一下"))));
    }

    @Test
    void handle_should_not_repeat_status_in_summary_when_cancelled() {
        // Given
        when(launcher.run(any(SubAgentCall.class), any(ReActListener.class)))
                .thenReturn(SubAgentOutcome.cancelled(2, usage(50L)));

        // When / Then：界面已经有一个 `⚠ CANCELLED` 后缀，摘要里再写一遍是同一件事说两遍
        assertEquals("子代理 scout · 2 轮 · 50 tok", summaryOf(tool.handle(request("scout", "查一下"))));
    }

    @Test
    void handle_should_mark_terminal_and_show_reason_when_rejected() {
        // Given
        when(launcher.run(any(SubAgentCall.class), any(ReActListener.class)))
                .thenReturn(SubAgentOutcome.rejected("未知的子代理类型：ghost；可用：scout"));

        // When
        ToolCallResult result = tool.handle(request("ghost", "查一下"));

        // Then：界面要能一眼看出这次没成，模型要拿到可修复的原因
        assertTrue(result.getOutput().toString().startsWith("[子代理未开始]"));
        assertTrue(result.getOutput().toString().contains("可用：scout"));
        assertTrue(ToolMetadata.failed(result.getMetadata()));
        assertEquals("rejected", result.getMetadata().get("status"));
    }

    @Test
    void handle_should_mark_terminal_when_cancelled() {
        // Given
        when(launcher.run(any(SubAgentCall.class), any(ReActListener.class)))
                .thenReturn(SubAgentOutcome.cancelled(1, SessionUsage.EMPTY));

        // When
        ToolCallResult result = tool.handle(request("scout", "查一下"));

        // Then：取消也没有文本，同样不是「正常跑完」
        assertTrue(result.getOutput().toString().contains("已取消"));
        assertTrue(ToolMetadata.failed(result.getMetadata()));
    }

    @Test
    void handle_should_forward_nested_tool_starts_to_sink() {
        // Given：委派器在跑的过程中回调进度
        when(launcher.run(any(SubAgentCall.class), any(ReActListener.class))).thenAnswer(invocation -> {
            ReActListener listener = invocation.getArgument(1, ReActListener.class);
            listener.onToolCallStarted("c1", "read_file");
            listener.onToolCallStarted("c2", "grep_files");
            return SubAgentOutcome.completed("结论", 2, SessionUsage.EMPTY);
        });

        // When
        tool.handle(request("scout", "查一下登录流程"));

        // Then：开场 + 每一步各一行，用户能看到子代理在干什么
        assertEquals(3, sink.chunks.size());
        assertTrue(sink.chunks.get(0).startsWith("[scout] 查一下"), sink.chunks.get(0));
        assertEquals("  · read_file\n", sink.chunks.get(1));
        assertEquals("  · grep_files\n", sink.chunks.get(2));
    }

    @Test
    void handle_should_not_forward_nested_text_deltas() {
        // Given：子代理刷了很多正文增量
        when(launcher.run(any(SubAgentCall.class), any(ReActListener.class))).thenAnswer(invocation -> {
            ReActListener listener = invocation.getArgument(1, ReActListener.class);
            listener.onText("第一段");
            listener.onText("第二段");
            listener.onThinking("想一下");
            return SubAgentOutcome.completed("结论", 2, SessionUsage.EMPTY);
        });

        // When
        tool.handle(request("scout", "查一下登录流程"));

        // Then：只留下开场那一行——正文会在结束时整段给出，增量转发会把界面刷满两份交织的流
        assertEquals(1, sink.chunks.size());
    }

    /**
     * 取一次结果的单行摘要。
     *
     * @param result 工具结果
     * @return 摘要文本
     */
    private static String summaryOf(ToolCallResult result) {
        return ToolMetadata.summaryOf(result.getMetadata());
    }

    /**
     * 构造一次工具调用请求。
     *
     * @param agentId 子代理类型
     * @param prompt  任务原文
     * @return 工具调用请求
     */
    private ToolCallRequest request(String agentId, String prompt) {
        Map<String, Object> arguments = new HashMap<String, Object>();
        arguments.put("subagent_type", agentId);
        arguments.put("description", "查一下");
        arguments.put("prompt", prompt);
        return new ToolCallRequest(TaskTool.NAME, arguments, "session-1", null, sink);
    }

    /**
     * 捕获委派请求并断言监听器非空。
     *
     * @param captor 捕获器
     */
    private void verifyCall(ArgumentCaptor<SubAgentCall> captor) {
        verify(launcher).run(captor.capture(), any(ReActListener.class));
        assertNotNull(captor.getValue());
    }

    /**
     * 构造带指定总 token 的用量。
     *
     * @param totalTokens 总 token 数
     * @return 用量快照
     */
    private static SessionUsage usage(long totalTokens) {
        return new SessionUsage(totalTokens, 0L, totalTokens, 1L);
    }

    /**
     * 录制型输出通道：把写入的片段收集到内存，供断言使用。
     *
     * @author zcd
     */
    private static final class RecordingSink implements ToolOutputSink {

        /** 已写入的片段。 */
        private final List<String> chunks = new ArrayList<String>();

        @Override
        public void write(String chunk) {
            chunks.add(chunk);
        }

        @Override
        public void summary(String text) {
            chunks.add(text);
        }

        @Override
        public String finish() {
            return null;
        }
    }
}
