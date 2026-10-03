package zcd.jellyfish.core.subagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.ui.UiRegion;
import zcd.jellyfish.core.runtime.AgentRunRequest;
import zcd.jellyfish.core.runtime.AgentRuntime;
import zcd.jellyfish.core.runtime.RunRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

/**
 * {@link SubAgentPanel} 的单元测试：按父会话筛选与行渲染。
 * <p>
 * 快照用真实的 {@link RunRegistry} 造（它的构造器是包内可见的，跨包造不出来，也不该为测试放开），
 * 只把 {@link AgentRuntime#activeRuns()} 换成登记表的内容——面板要验证的正是「登记表里那些 run，
 * 哪些该出现在这个会话的界面上」。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
class SubAgentPanelTest {

    /** 被测面板的运行时门面。 */
    @Mock
    private AgentRuntime runtime;

    /** 真实登记表，用来造出合法的快照。 */
    private RunRegistry registry;

    /** 被测对象。 */
    private SubAgentPanel panel;

    @BeforeEach
    void setUp() {
        registry = new RunRegistry();
        panel = new SubAgentPanel(runtime);
    }

    @Test
    void handle_should_render_only_runs_of_the_requested_session() {
        // Given：一个属于 s1 的 run，一个属于别的会话的 run
        String mine = register("s1", "coder");
        register("s2", "reviewer");
        registry.markRunning(mine);
        lenient().when(runtime.activeRuns()).thenReturn(registry.active());

        // When
        PanelContribution contribution = panel.handle(new PanelContributionRequest("s1"));

        // Then：只有本会话那一个，且落在右侧区域
        assertEquals("子代理", contribution.getTitle());
        assertEquals(UiRegion.RIGHT, contribution.getPreferredRegion());
        assertEquals(1, contribution.getLines().size());
        String text = contribution.getLines().get(0).text();
        assertTrue(text.startsWith("coder · "), text);
        assertTrue(text.contains("已运行 "), text);
    }

    @Test
    void handle_should_render_one_line_per_run_in_start_order() {
        // Given：同一会话里两个并行 run
        registry.markRunning(register("s1", "coder"));
        registry.markRunning(register("s1", "reviewer"));
        lenient().when(runtime.activeRuns()).thenReturn(registry.active());

        // When
        PanelContribution contribution = panel.handle(new PanelContributionRequest("s1"));

        // Then：并行的第二个必须也能看到——只显示一个正是「看不到并行」这个问题的原形
        assertEquals(2, contribution.getLines().size());
    }

    @Test
    void handle_should_return_empty_when_session_has_no_run() {
        // Given：只有别的会话在跑
        registry.markRunning(register("s2", "coder"));
        lenient().when(runtime.activeRuns()).thenReturn(registry.active());

        // When / Then：空贡献 = 不占区域
        assertTrue(panel.handle(new PanelContributionRequest("s1")).isEmpty());
    }

    @Test
    void handle_should_return_empty_when_session_id_missing() {
        registry.markRunning(register("s1", "coder"));
        lenient().when(runtime.activeRuns()).thenReturn(registry.active());

        // 没有会话上下文时宁可空着，也不把别的会话的子代理贴到当前界面上
        assertTrue(panel.handle(new PanelContributionRequest(null)).isEmpty());
    }

    /**
     * 登记一个 run 并返回其标识。
     *
     * @param parentSessionId 父会话标识
     * @param agentId         agent 标识
     * @return run 标识
     */
    private String register(String parentSessionId, String agentId) {
        return registry.register(new AgentRunRequest(parentSessionId, agentId, agentId + "-child", null),
                null, null);
    }
}
