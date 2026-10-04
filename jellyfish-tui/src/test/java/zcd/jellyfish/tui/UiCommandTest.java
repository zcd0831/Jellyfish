package zcd.jellyfish.tui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.CommandChoice;
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

    /**
     * 取某个取值的候选。
     *
     * @param result 结果
     * @param value  取值
     * @return 候选；没有这个取值时返回 {@code null}
     */
    private static CommandChoice choiceOf(UiCommand.Result result, String value) {
        for (CommandChoice choice : result.getChoices()) {
            if (choice.getValue().equals(value)) {
                return choice;
            }
        }
        return null;
    }

    /**
     * 取某个取值候选的说明文本。
     *
     * @param result 结果
     * @param value  取值
     * @return 说明文本，保证非 {@code null}
     */
    private static String descriptionOf(UiCommand.Result result, String value) {
        CommandChoice choice = choiceOf(result, value);
        assertNotNull(choice, "候选里应有 " + value);
        return String.valueOf(choice.getDescription());
    }

    /**
     * 判断某个取值是否被标成「当前」。
     *
     * @param result 结果
     * @param value  取值
     * @return 是当前取值返回 {@code true}
     */
    private static boolean currentOf(UiCommand.Result result, String value) {
        CommandChoice choice = choiceOf(result, value);
        assertNotNull(choice, "候选里应有 " + value);
        return choice.isCurrent();
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
    @DisplayName("无参数时给出区域候选（二级页），且不改动任何显示状态")
    void execute_should_offerRegions_when_noArguments() {
        UiPlacement placement = new UiPlacement();
        List<OwnedPanel> panels = twoInDock();

        UiCommand.Result result = UiCommand.execute("/ui", placement, panels, null);

        assertFalse(result.isError());
        assertTrue(result.hasChoices());
        assertEquals(5, result.getChoices().size(), "五个区域各一条候选");
        assertEquals("dock", result.getChoices().get(0).getValue(), "面板区域在前、状态栏殿后");
        assertEquals("status", result.getChoices().get(4).getValue());
        assertEquals("alpha", placement.selected(panels).get(UiRegion.DOCK).getOwner(),
                "给候选不该改动落位");
    }

    @Test
    @DisplayName("区域候选带上「现在是显示还是关闭、显示的是谁」")
    void execute_should_describeRegions_when_offeringChoices() {
        UiPlacement placement = new UiPlacement();
        placement.hide(UiRegion.DOCK);

        UiCommand.Result result = UiCommand.execute("/ui", placement, twoInDock(), null);

        String dock = descriptionOf(result, "dock");
        assertTrue(dock.contains("关闭"), dock);
        assertTrue(dock.contains("alpha"), "被关闭时回落到候选首位，好让用户知道重开会看到谁：" + dock);
        assertTrue(descriptionOf(result, "dock").contains("另有 1 个"),
                "要告诉用户还有候选被挤下去");
        // 没有候选的区域说「无贡献」而不是「关闭」：关不关它都没有东西可显示，
        // 而「无贡献」才是用户需要知道的那件事
        assertTrue(descriptionOf(result, "top").contains("无贡献"));
    }

    @Test
    @DisplayName("list 子命令仍给纯文本清单：快捷键诊断塞不进候选")
    void execute_should_renderListText_when_listSubcommand() {
        UiPlacement placement = new UiPlacement();
        List<OwnedPanel> panels = twoInDock();

        UiCommand.Result result = UiCommand.execute("/ui list", placement, panels, Collections.singletonList(
                "ctrl+x → /nope（命令不存在）"));

        assertFalse(result.isError());
        assertFalse(result.hasChoices(), "list 是文本入口，不弹选择页");
        assertTrue(result.getText().contains("alpha"));
        assertTrue(result.getText().contains("beta"));
        assertTrue(result.getText().contains("用法"), "清单应给出用法说明");
        assertTrue(result.getText().contains("ctrl+x"), "未生效的插件快捷键只有这条路报得出来");
        assertEquals("alpha", placement.selected(panels).get(UiRegion.DOCK).getOwner(),
                "列清单不该改动落位");
    }

    @Test
    @DisplayName("带区域无动作时给出该区域的候选（三级页）")
    void execute_should_offerRegionActions_when_onlyRegion() {
        UiPlacement placement = new UiPlacement();
        List<OwnedPanel> panels = twoInDock();

        UiCommand.Result result = UiCommand.execute("/ui dock", placement, panels, null);

        assertFalse(result.isError());
        assertTrue(result.hasChoices());
        assertEquals(4, result.getChoices().size(), "两个插件 + on / off");
        assertEquals("alpha", result.getChoices().get(0).getValue());
        assertEquals("beta", result.getChoices().get(1).getValue());
        assertEquals("on", result.getChoices().get(2).getValue());
        assertEquals("off", result.getChoices().get(3).getValue());
        assertEquals("alpha", placement.selected(panels).get(UiRegion.DOCK).getOwner(),
                "给候选不该改动落位");
    }

    @Test
    @DisplayName("三级页把当前显示的那个标成「当前」：用户据此知道自己在看谁的面板")
    void execute_should_markCurrentChoice_when_offeringRegionActions() {
        UiPlacement placement = new UiPlacement();
        List<OwnedPanel> panels = twoInDock();
        assertTrue(currentOf(UiCommand.execute("/ui dock", placement, panels, null), "alpha"),
                "默认显示 order 最小的那个");

        UiCommand.execute("/ui dock beta", placement, panels, null);

        assertTrue(currentOf(UiCommand.execute("/ui dock", placement, panels, null), "beta"),
                "指定过之后，「当前」应跟着走");
    }

    @Test
    @DisplayName("区域没有候选时三级页只给 on / off，不报错")
    void execute_should_offerOnlyToggle_when_regionHasNoPanel() {
        UiPlacement placement = new UiPlacement();

        UiCommand.Result result = UiCommand.execute("/ui left", placement, twoInDock(), null);

        assertFalse(result.isError());
        assertTrue(result.hasChoices());
        assertEquals(2, result.getChoices().size());
        assertEquals("on", result.getChoices().get(0).getValue());
        assertEquals("off", result.getChoices().get(1).getValue());
    }

    @Test
    @DisplayName("状态栏是拼接型：三级页只有 on / off，没有可指定的 pluginId")
    void execute_should_offerOnlyToggle_when_statusRegion() {
        UiPlacement placement = new UiPlacement();

        UiCommand.Result result = UiCommand.execute("/ui status", placement, twoInDock(), null);

        assertFalse(result.isError());
        assertTrue(result.hasChoices());
        assertEquals(2, result.getChoices().size(), "状态栏不能指定插件");
        assertEquals("on", result.getChoices().get(0).getValue());
        assertEquals("off", result.getChoices().get(1).getValue());
        assertTrue(descriptionOf(result, "on").contains("恢复"), "候选要说清自己干了什么");
    }

    @Test
    @DisplayName("cycle 子命令接替原来的「带区域即轮换」")
    void execute_should_cycle_when_cycleSubcommand() {
        UiPlacement placement = new UiPlacement();
        List<OwnedPanel> panels = twoInDock();

        UiCommand.Result result = UiCommand.execute("/ui dock cycle", placement, panels, null);

        assertFalse(result.hasChoices());
        assertTrue(result.getText().contains("beta"));
        assertEquals("beta", placement.selected(panels).get(UiRegion.DOCK).getOwner());
    }

    @Test
    @DisplayName("指定 pluginId 时以用户为准")
    void execute_should_assignPlugin() {
        UiPlacement placement = new UiPlacement();
        List<OwnedPanel> panels = twoInDock();

        UiCommand.Result result = UiCommand.execute("/ui dock beta", placement, panels, null);

        assertFalse(result.isError());
        assertEquals("beta", placement.selected(panels).get(UiRegion.DOCK).getOwner());
    }

    @Test
    @DisplayName("off 关闭区域、on 恢复，且候选不消失")
    void execute_should_toggleRegion() {
        UiPlacement placement = new UiPlacement();
        List<OwnedPanel> panels = twoInDock();

        assertFalse(UiCommand.execute("/ui dock off", placement, panels, null).isError());
        assertTrue(placement.isHidden(UiRegion.DOCK));

        assertFalse(UiCommand.execute("/ui dock on", placement, panels, null).isError());
        assertFalse(placement.isHidden(UiRegion.DOCK));
        assertNotNull(placement.selected(panels).get(UiRegion.DOCK));
    }

    @Test
    @DisplayName("未知区域报错并给出用法，不改动状态")
    void execute_should_rejectUnknownRegion() {
        UiPlacement placement = new UiPlacement();

        UiCommand.Result result = UiCommand.execute("/ui nowhere", placement, twoInDock(), null);

        assertTrue(result.isError());
        assertTrue(result.getText().contains("nowhere"));
        assertTrue(result.getText().contains("用法"));
    }

    @Test
    @DisplayName("在不含该插件的区域指定它时报错，并顺便列出清单")
    void execute_should_rejectPluginWithoutCandidate() {
        UiPlacement placement = new UiPlacement();

        UiCommand.Result result = UiCommand.execute("/ui left alpha", placement, twoInDock(), null);

        assertTrue(result.isError());
        assertTrue(result.getText().contains("alpha"));
        assertTrue(result.getText().contains("dock"), "报错应附上清单，告诉用户 alpha 其实在 dock");
    }

    @Test
    @DisplayName("状态栏不支持指定插件：它是拼接型，只能 off / on")
    void execute_should_rejectAssignOnStatusRegion() {
        UiPlacement placement = new UiPlacement();

        assertTrue(UiCommand.execute("/ui status alpha", placement, twoInDock(), null).isError());
        assertFalse(UiCommand.execute("/ui status off", placement, twoInDock(), null).isError());
        assertTrue(placement.isHidden(UiRegion.STATUS));
        assertFalse(UiCommand.execute("/ui status on", placement, twoInDock(), null).isError());
        assertFalse(placement.isHidden(UiRegion.STATUS));
    }

    @Test
    @DisplayName("区域没有候选时 cycle 给出「没有插件面板」而不是报错")
    void execute_should_reportEmptyRegion() {
        UiPlacement placement = new UiPlacement();

        UiCommand.Result result = UiCommand.execute("/ui left cycle", placement, twoInDock(), null);

        assertFalse(result.isError());
        assertFalse(result.hasChoices());
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

        UiCommand.Result result = UiCommand.execute("/ui dock beta extra", placement, twoInDock(), null);

        assertFalse(result.isError());
        assertEquals("beta", placement.selected(twoInDock()).get(UiRegion.DOCK).getOwner());
    }
}
