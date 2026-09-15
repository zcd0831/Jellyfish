package zcd.jellyfish.infra.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.api.ui.UiRegion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UiSnapshot} 的单元测试。
 *
 * @author zcd
 */
@DisplayName("UI 贡献快照")
class UiSnapshotTest {

    /**
     * 构造一块最小可用面板。
     *
     * @param owner 贡献方
     * @return 带归属的面板
     */
    private static OwnedPanel panelOf(String owner) {
        return new OwnedPanel(owner, PanelContribution.of("标题",
                Collections.singletonList(UiLine.of("正文")), UiRegion.DOCK));
    }

    @Test
    @DisplayName("空快照既没有片段也没有面板")
    void empty_should_haveNoContributions() {
        assertTrue(UiSnapshot.empty().isEmpty());
        assertTrue(UiSnapshot.empty().getStatusFragments().isEmpty());
        assertTrue(UiSnapshot.empty().getPanels().isEmpty());
    }

    @Test
    @DisplayName("null 与两个空列表都归一为空快照")
    void of_should_returnEmpty_when_nothingToShow() {
        assertSame(UiSnapshot.empty(), UiSnapshot.of(null, null));
        assertSame(UiSnapshot.empty(),
                UiSnapshot.of(Collections.<String>emptyList(), Collections.<OwnedPanel>emptyList()));
    }

    @Test
    @DisplayName("只有片段时不归空：两者都是独立贡献，任一非空就算有内容")
    void of_should_notBeEmpty_when_onlyFragments() {
        UiSnapshot snapshot = UiSnapshot.of(Arrays.asList("待办 2/5"), null);
        assertEquals(1, snapshot.getStatusFragments().size());
        assertTrue(snapshot.getPanels().isEmpty());
        assertTrue(!snapshot.isEmpty());
    }

    @Test
    @DisplayName("只有面板时不归空")
    void of_should_notBeEmpty_when_onlyPanels() {
        UiSnapshot snapshot = UiSnapshot.of(null, Arrays.asList(panelOf("jellyfish-todo")));
        assertTrue(snapshot.getStatusFragments().isEmpty());
        assertEquals(1, snapshot.getPanels().size());
        assertTrue(!snapshot.isEmpty());
    }

    @Test
    @DisplayName("保留片段顺序与面板顺序：它们就是注册顺序（面板已按 order 升序）")
    void of_should_keepOrder() {
        UiSnapshot snapshot = UiSnapshot.of(Arrays.asList("a", "b"),
                Arrays.asList(panelOf("p1"), panelOf("p2")));

        assertEquals(Arrays.asList("a", "b"), snapshot.getStatusFragments());
        assertEquals("p1", snapshot.getPanels().get(0).getOwner());
        assertEquals("p2", snapshot.getPanels().get(1).getOwner());
    }

    @Test
    @DisplayName("拷贝来源列表：外部后续改动不影响快照")
    void of_should_copySourceLists() {
        List<String> fragments = new ArrayList<String>();
        fragments.add("a");
        List<OwnedPanel> panels = new ArrayList<OwnedPanel>();
        panels.add(panelOf("p1"));

        UiSnapshot snapshot = UiSnapshot.of(fragments, panels);
        fragments.add("b");
        panels.add(panelOf("p2"));

        assertEquals(1, snapshot.getStatusFragments().size());
        assertEquals(1, snapshot.getPanels().size());
    }

    @Test
    @DisplayName("两个列表都只读：快照要能跨线程安全读取")
    void getters_should_beUnmodifiable() {
        UiSnapshot snapshot = UiSnapshot.of(Collections.singletonList("a"),
                Collections.singletonList(panelOf("p1")));

        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.getStatusFragments().add("b"));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.getPanels().add(panelOf("p2")));
    }
}
