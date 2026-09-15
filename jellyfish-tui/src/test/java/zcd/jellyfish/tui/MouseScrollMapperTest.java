package zcd.jellyfish.tui;

import dev.tamboui.tui.event.MouseButton;
import dev.tamboui.tui.event.MouseEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link MouseScrollMapper} 的单元测试。
 * <p>
 * 重点锁住「只认上下滚轮」：鼠标捕获开启后，按下 / 抬起 / 拖动也会送到全局处理器，
 * 一旦被误判成滚动，点击屏幕就会变成停不下来的滚动。
 *
 * @author zcd
 */
@DisplayName("MouseScrollMapper 滚轮判定")
class MouseScrollMapperTest {

    @Test
    @DisplayName("上滚应判定为向上滚动")
    void map_should_returnScrollUp_when_wheelUp() {
        assertEquals(Optional.of(InputAction.SCROLL_UP), MouseScrollMapper.map(MouseEvent.scrollUp(10, 5)));
    }

    @Test
    @DisplayName("下滚应判定为向下滚动")
    void map_should_returnScrollDown_when_wheelDown() {
        assertEquals(Optional.of(InputAction.SCROLL_DOWN), MouseScrollMapper.map(MouseEvent.scrollDown(10, 5)));
    }

    @Test
    @DisplayName("左右滚动没有对应动作：消息区是单栏纯文本")
    void map_should_returnEmpty_when_horizontalScroll() {
        assertFalse(MouseScrollMapper.map(MouseEvent.scrollLeft(10, 5)).isPresent());
        assertFalse(MouseScrollMapper.map(MouseEvent.scrollRight(10, 5)).isPresent());
    }

    @Test
    @DisplayName("按下 / 抬起 / 移动不得被当成滚轮，否则点击会变成滚动")
    void map_should_returnEmpty_when_notWheel() {
        assertFalse(MouseScrollMapper.map(MouseEvent.press(MouseButton.LEFT, 10, 5)).isPresent());
        assertFalse(MouseScrollMapper.map(MouseEvent.release(MouseButton.LEFT, 10, 5)).isPresent());
        assertFalse(MouseScrollMapper.map(MouseEvent.move(10, 5)).isPresent());
    }

    @Test
    @DisplayName("滚轮步长必须为正，且小于整页：滚轮是微调，翻页另有键位")
    void wheelStepRows_should_beSmallPositive() {
        assertEquals(3, MouseScrollMapper.WHEEL_STEP_ROWS);
    }
}
