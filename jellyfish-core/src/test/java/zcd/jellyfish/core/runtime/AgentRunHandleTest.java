package zcd.jellyfish.core.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentRunHandle} 的超时标记时效。
 * <p>
 * 这里钉的是「超时算不算数」这条判据本身，而不是某个并发交错：看门狗的任务排在墙钟到点那一刻才被
 * 调度到，而那一刻 run 可能已经跑完收尾了。判据一旦退化成「定时器响过就算超时」，一个完整成功的结果
 * 就会被改标成「截断」并回灌「请调大 runTimeoutMillis」，模型据此去调配置、甚至重跑一遍。
 * <p>
 * 之所以把窗口做成这个形状：看门狗与执行体之间的窗口在生产代码里只有几行、无法注入停顿点，
 * 而这条判据是那个窗口里唯一的决定因素。
 *
 * @author zcd
 */
@DisplayName("AgentRunHandle 超时标记")
class AgentRunHandleTest {

    @Test
    @DisplayName("run 还在跑时，超时标记生效")
    void markTimedOut_should_count_whileRunning() {
        AgentRunHandle handle = new AgentRunHandle("r-running");

        assertTrue(handle.markTimedOut());
        assertTrue(handle.isTimedOut());
    }

    @Test
    @DisplayName("执行体已经返回后，超时标记不再生效")
    void markTimedOut_should_notCount_afterBodyFinished() {
        // Given：执行体跑完了，只剩下收尾（落终态、广播、开闸）
        AgentRunHandle handle = new AgentRunHandle("r-finished");
        handle.markBodyFinished();

        // When：看门狗恰好在这一刻被调度到
        boolean counted = handle.markTimedOut();

        // Then：它的结果没被墙钟打断，就不该被说成截断
        assertFalse(counted);
        assertFalse(handle.isTimedOut());
    }

    @Test
    @DisplayName("落终态之后，超时标记不再生效")
    void markTimedOut_should_notCount_afterCompleted() {
        AgentRunHandle handle = new AgentRunHandle("r-done");
        handle.complete(AgentRunResult.of(AgentRunStatus.DONE, "结论", 1, null, null));

        assertFalse(handle.markTimedOut());
        assertFalse(handle.isTimedOut());
        assertTrue(handle.isDone());
    }
}
