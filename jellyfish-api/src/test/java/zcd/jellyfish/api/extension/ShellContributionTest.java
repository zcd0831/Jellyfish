package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.api.ui.UiSegment;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShellContribution} 的单元测试。
 * <p>
 * 关注两件事：工厂钉住的字段组合，以及「插件拿不到第四种 kind / 拿不到公开构造器」这条封闭性。
 *
 * @author zcd
 */
@DisplayName("外壳贡献")
class ShellContributionTest {

    @Test
    @DisplayName("通知带上作用域、key、严重程度与内容行")
    void notice_should_carryAllFields() {
        List<UiLine> lines = Arrays.asList(UiLine.of("已索引"), UiLine.of(UiSegment.of("12/40")));

        ShellContribution contribution = ShellContribution.notice(
                ShellContribution.Scope.SESSION, "s1", "progress", ShellContribution.Severity.WARN, lines);

        assertEquals(ShellContribution.Kind.NOTICE, contribution.getKind());
        assertEquals(ShellContribution.Scope.SESSION, contribution.getScope());
        assertEquals("s1", contribution.getSessionId());
        assertEquals("progress", contribution.getKey());
        assertEquals(ShellContribution.Severity.WARN, contribution.getSeverity());
        assertEquals(2, contribution.getLines().size());
        assertNull(contribution.getWhat());
    }

    @Test
    @DisplayName("严重程度缺省为中性：插件不必表态，外壳照常显示")
    void notice_should_defaultSeverityToInfo() {
        ShellContribution contribution = ShellContribution.notice(
                ShellContribution.Scope.SHELL, null, null, null, Arrays.asList(UiLine.of("x")));

        assertEquals(ShellContribution.Severity.INFO, contribution.getSeverity());
    }

    @Test
    @DisplayName("空内容行是「不显示」而不是「清空」：它退化成空列表而不是 null")
    void notice_should_normalizeEmptyLines() {
        assertEquals(0, ShellContribution.notice(ShellContribution.Scope.SHELL, null, null, null, null)
                .getLines().size());
        assertEquals(0, ShellContribution.notice(ShellContribution.Scope.SHELL, null, null, null,
                new ArrayList<UiLine>()).getLines().size());
    }

    @Test
    @DisplayName("内容行不可变：插件改不回自己交出去的那份")
    void notice_should_copyLines() {
        List<UiLine> lines = new ArrayList<UiLine>();
        lines.add(UiLine.of("a"));

        ShellContribution contribution = ShellContribution.notice(
                ShellContribution.Scope.SHELL, null, null, null, lines);
        lines.add(UiLine.of("b"));

        assertEquals(1, contribution.getLines().size());
        assertThrows(UnsupportedOperationException.class, () -> contribution.getLines().add(UiLine.of("c")));
    }

    @Test
    @DisplayName("失效提示没有内容、没有 key，只有一段定位线索")
    void invalidated_should_carryOnlyWhat() {
        ShellContribution contribution =
                ShellContribution.invalidated(ShellContribution.Scope.SESSION, "s1", "panel");

        assertEquals(ShellContribution.Kind.INVALIDATED, contribution.getKind());
        assertEquals(ShellContribution.Scope.SESSION, contribution.getScope());
        assertEquals("s1", contribution.getSessionId());
        assertEquals("panel", contribution.getWhat());
        assertNull(contribution.getKey());
        assertTrue(contribution.getLines().isEmpty());
    }

    @Test
    @DisplayName("线索可以为空：那表示「我的全部贡献都脏了」，外壳可以忽略它并全量重拉")
    void invalidated_should_allowNullWhat() {
        assertNull(ShellContribution.invalidated(ShellContribution.Scope.SHELL, null, null).getWhat());
    }

    @Test
    @DisplayName("作用域不可为空：它是结构性字段，缺了就不知道往哪儿送")
    void factories_should_rejectNullScope() {
        assertThrows(JellyfishException.class,
                () -> ShellContribution.notice(null, null, null, null, null));
        assertThrows(JellyfishException.class,
                () -> ShellContribution.invalidated(null, null, null));
    }

    @Test
    @DisplayName("SESSION scope 缺会话标识不算构造错误：投递那一刻才回报 DROPPED_NO_SESSION")
    void notice_should_allowBlankSessionOnConstruction() {
        // 校验点刻意放在投递侧：构造期报错会让「先造好、稍后再发」这种写法无处安放，
        // 而它恰恰是插件最自然的写法
        assertEquals("", ShellContribution.notice(
                ShellContribution.Scope.SESSION, "", null, null, null).getSessionId());
    }

    @Test
    @DisplayName("刻意没有值相等：内容里的 UiLine 是身份比较的，按字段比只会给出误导性的 false")
    void should_notDefineValueEquality() {
        ShellContribution a = ShellContribution.notice(ShellContribution.Scope.SESSION, "s1", "k",
                ShellContribution.Severity.INFO, Arrays.asList(UiLine.of("x")));
        ShellContribution same = ShellContribution.notice(ShellContribution.Scope.SESSION, "s1", "k",
                ShellContribution.Severity.INFO, Arrays.asList(UiLine.of("x")));

        assertNotEquals(a, same);
        assertEquals(a, a);
        assertNotEquals(a, ShellContribution.invalidated(ShellContribution.Scope.SESSION, "s1", "k"));
    }

    @Test
    @DisplayName("没有公开构造器：kind 与作用域的合法组合只能出自那两个工厂")
    void should_haveNoVisibleConstructor() {
        // 「插件不能扩展 kind」这条承诺必须落在类型上，而不是文档上：多出一个可见构造器，
        // 插件就能造出 NOTICE 里塞 what、或 INVALIDATED 里塞 lines 这种半成品
        for (Constructor<?> constructor : ShellContribution.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()),
                    "构造器必须保持私有：" + constructor);
        }
    }

    @Test
    @DisplayName("String 化不抛异常：它会被打进日志与诊断输出")
    void toString_should_notThrow() {
        assertTrue(ShellContribution.invalidated(ShellContribution.Scope.SHELL, null, "x")
                .toString().contains("INVALIDATED"));
        assertTrue(ShellContribution.notice(ShellContribution.Scope.SHELL, null, null, null, null)
                .toString().contains("NOTICE"));
    }

    @Test
    @DisplayName("作用域与种类都是封闭枚举：插件没有别的取值可传")
    void enums_should_beClosed() {
        assertEquals(2, ShellContribution.Scope.values().length);
        assertEquals(2, ShellContribution.Kind.values().length);
        assertEquals(3, ShellContribution.Severity.values().length);
    }
}
