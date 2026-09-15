package zcd.jellyfish.infra.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    @Test
    @DisplayName("空快照没有任何片段")
    void empty_should_haveNoFragments() {
        assertTrue(UiSnapshot.empty().isEmpty());
        assertTrue(UiSnapshot.empty().getStatusFragments().isEmpty());
    }

    @Test
    @DisplayName("null 与空列表都归一为空快照")
    void of_should_returnEmpty_when_noFragments() {
        assertSame(UiSnapshot.empty(), UiSnapshot.of(null));
        assertSame(UiSnapshot.empty(), UiSnapshot.of(Collections.<String>emptyList()));
    }

    @Test
    @DisplayName("保留片段顺序：它就是注册顺序")
    void of_should_keepOrder() {
        UiSnapshot snapshot = UiSnapshot.of(Arrays.asList("a", "b"));

        assertEquals(Arrays.asList("a", "b"), snapshot.getStatusFragments());
        assertEquals(2, snapshot.getStatusFragments().size());
    }

    @Test
    @DisplayName("拷贝来源列表：外部后续改动不影响快照")
    void of_should_copySourceList() {
        List<String> source = new ArrayList<String>();
        source.add("a");

        UiSnapshot snapshot = UiSnapshot.of(source);
        source.add("b");

        assertEquals(1, snapshot.getStatusFragments().size());
    }

    @Test
    @DisplayName("片段列表只读：快照要能跨线程安全读取")
    void getStatusFragments_should_beUnmodifiable() {
        UiSnapshot snapshot = UiSnapshot.of(Collections.singletonList("a"));

        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.getStatusFragments().add("b"));
    }
}
