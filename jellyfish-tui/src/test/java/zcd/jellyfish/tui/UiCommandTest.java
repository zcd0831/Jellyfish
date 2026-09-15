package zcd.jellyfish.tui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.api.ui.UiRegion;
import zcd.jellyfish.infra.ui.OwnedPanel;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UiCommand} 的单元测试。
 *
 * @author zcd
 */
@DisplayName("/ui 命令")
class UiCommandTest {

    /**
     * 构造一个面板。
     *
     * @param owner  贡献方
     * @param region 建议区域，可为 {@code null}
     * @return 带归属的面板
     */
    private static OwnedPanel panelOf(String owner, UiRegion region) {
        return new OwnedPanel(owner, PanelContribution.of(owner + " 面板",
                Collections.singletonList(UiLine.of("正文")), region));
    }

    /**
     * 构造两个抢同一区域的面板。
     *
     * @return 候选列表
     */
    private static List<OwnedPanel> twoInDock() {
        return Arrays.asList(panelOf("alpha", UiRegion.DOCK), panelOf("beta", UiRegion.DOCK));
    }

    @Test
    @DisplayName("判定只看首词：带参数的 /ui 也命中")
    void isUi_should_matchAnyArguments() {
        assertTrue(UiCommand.isUi("/ui"));
        assertTrue(UiCommand.isUi("/ui dock"));
        assertTrue(UiCommand.isUi("  /ui  dock  alpha  "));
        assertFalse(UiCommand.isUi("/uix"));
        assertFalse(UiCommand.isUi("/exit"));
        assertFalse(UiCommand.isUi(null));
    }

    @Test
    @DisplayName("无参数时只列清单，不改变任何显示状态")
    void execute_should_listOnly_when_noArguments() {
        UiPlacement placement = new UiPlacement();
        List<OwnedPanel> panels = twoInDock();

        UiCommand.Result result = UiCommand.execute("/ui", placement, panels);

        assertFalse(result.isError());
        assertTrue(result.getText().contains("alpha"));
        assertTrue(result.getText().contains("beta"));
        assertTrue(result.getText().contains("/ui"), "清单应给出用法说明");
        assertEquals("alpha", placement.selected(panels).get(UiRegion.DOCK).getOwner(),
                "列清单不该改动落位");
    }

    @Test
    @DisplayName("带区域无动作时轮换到下一个候选")
    void execute_should_cycle_when_onlyRegion() {
        UiPlacement placement = new UiPlacement();
        List<OwnedPanel> panels = twoInDock();

        UiCommand.Result result = UiCommand.execute("/ui dock", placement, panels);

        assertTrue(result.getText().contains("beta"));
        assertEquals("beta", placement.selected(panels).get(UiRegion.DOCK).getOwner());
    }

    @Test
    @DisplayName("指定 pluginId 时以用户为准")
    void execute_should_assignPlugin() {
        UiPlacement placement = new UiPlacement();
        List<OwnedPanel> panels = twoInDock();

        UiCommand.Result result = UiCommand.execute("/ui dock beta", placement, panels);

        assertFalse(result.isError());
        assertEquals("beta", placement.selected(panels).get(UiRegion.DOCK).getOwner());
    }

    @Test
    @DisplayName("off 关闭区域、on 恢复，且候选不消失")
    void execute_should_toggleRegion() {
        UiPlacement placement = new UiPlacement();
        List<OwnedPanel> panels = twoInDock();

        assertFalse(UiCommand.execute("/ui dock off", placement, panels).isError());
        assertTrue(placement.isHidden(UiRegion.DOCK));

        assertFalse(UiCommand.execute("/ui dock on", placement, panels).isError());
        assertFalse(placement.isHidden(UiRegion.DOCK));
        assertNotNull(placement.selected(panels).get(UiRegion.DOCK));
    }

    @Test
    @DisplayName("未知区域报错并给出用法，不改动状态")
    void execute_should_rejectUnknownRegion() {
        UiPlacement placement = new UiPlacement();

        UiCommand.Result result = UiCommand.execute("/ui nowhere", placement, twoInDock());

        assertTrue(result.isError());
        assertTrue(result.getText().contains("nowhere"));
        assertTrue(result.getText().contains("用法"));
    }

    @Test
    @DisplayName("在不含该插件的区域指定它时报错，并顺便列出清单")
    void execute_should_rejectPluginWithoutCandidate() {
        UiPlacement placement = new UiPlacement();

        UiCommand.Result result = UiCommand.execute("/ui left alpha", placement, twoInDock());

        assertTrue(result.isError());
        assertTrue(result.getText().contains("alpha"));
        assertTrue(result.getText().contains("dock"), "报错应附上清单，告诉用户 alpha 其实在 dock");
    }

    @Test
    @DisplayName("状态栏不支持指定插件：它是拼接型，只能 off / on")
    void execute_should_rejectAssignOnStatusRegion() {
        UiPlacement placement = new UiPlacement();

        assertTrue(UiCommand.execute("/ui status alpha", placement, twoInDock()).isError());
        assertFalse(UiCommand.execute("/ui status off", placement, twoInDock()).isError());
        assertTrue(placement.isHidden(UiRegion.STATUS));
        assertFalse(UiCommand.execute("/ui status", placement, twoInDock()).isError());
        assertFalse(placement.isHidden(UiRegion.STATUS));
    }

    @Test
    @DisplayName("区域没有候选时给出「没有插件面板」而不是报错")
    void execute_should_reportEmptyRegion() {
        UiPlacement placement = new UiPlacement();

        UiCommand.Result result = UiCommand.execute("/ui left", placement, twoInDock());

        assertFalse(result.isError());
        assertTrue(result.getText().contains("left"));
    }

    @Test
    @DisplayName("没有任何插件贡献时清单仍然可用，并说明原因")
    void renderList_should_workWithoutAnyContribution() {
        String text = UiCommand.renderList(new UiPlacement(), Collections.<OwnedPanel>emptyList());

        assertTrue(text.contains("无贡献"));
        assertTrue(text.contains("没有任何插件贡献界面内容"));
    }

    @Test
    @DisplayName("清单说明「还有别的候选」，否则用户看不到被挤下去的面板")
    void renderList_should_mentionOtherCandidates() {
        String text = UiCommand.renderList(new UiPlacement(), twoInDock());

        assertTrue(text.contains("另有 1 个候选"), text);
    }

    @Test
    @DisplayName("被关闭的区域在清单里标为关闭，并回落显示重新打开会看到谁")
    void renderList_should_markHiddenRegion() {
        UiPlacement placement = new UiPlacement();
        placement.hide(UiRegion.DOCK);

        String text = UiCommand.renderList(placement, twoInDock());

        assertTrue(text.contains("关闭"), text);
        assertTrue(text.contains("alpha"), text);
    }

    @Test
    @DisplayName("参数多于两个时忽略多余部分：多敲的空格不该变成错误")
    void execute_should_ignoreExtraArguments() {
        UiPlacement placement = new UiPlacement();

        UiCommand.Result result = UiCommand.execute("/ui dock beta extra", placement, twoInDock());

        assertFalse(result.isError());
        assertEquals("beta", placement.selected(twoInDock()).get(UiRegion.DOCK).getOwner());
    }
}
