package zcd.jellyfish.server;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.ShellContribution;
import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.api.ui.UiSegment;
import zcd.jellyfish.server.dto.ShellInvalidatedEvent;
import zcd.jellyfish.server.dto.ShellNoticeEvent;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SseContributionListener} 的「插件贡献 → SSE 事件」映射。
 * <p>
 * 重点有三条：按会话过滤、{@code SHELL} scope 发给每一条流、文本出线前已滤掉控制字符。
 *
 * @author zcd
 */
class SseContributionListenerTest {

    /** 本流对应的会话。 */
    private static final String SESSION = "s1";

    /** 造一个订阅者。 */
    private static SseContributionListener listener() {
        return new SseContributionListener(SESSION);
    }

    @Test
    void sessionScopedNotice_should_becomeShellNoticeEvent() {
        SseContributionListener listener = listener();

        listener.onContribution("plugin-a", ShellContribution.notice(ShellContribution.Scope.SESSION, SESSION,
                "progress", ShellContribution.Severity.WARN, lines("已索引 12/40")));

        SseEvent event = listener.pollNow();
        assertNotNull(event);
        assertEquals("shell_notice", event.getName());
        assertTrue(!event.isTerminal(), "贡献不是终态，不能提前关流");
        ShellNoticeEvent payload = (ShellNoticeEvent) event.getPayload();
        assertEquals("plugin-a", payload.getOwner());
        assertEquals(SESSION, payload.getSessionId());
        assertEquals("progress", payload.getKey());
        assertEquals("WARN", payload.getSeverity());
        assertEquals(Collections.singletonList("已索引 12/40"), payload.getLines());
    }

    @Test
    void sessionScopedNotice_should_beSkippedForAnotherSession() {
        // 并发客户端不能互相看到对方的通知
        SseContributionListener listener = listener();

        listener.onContribution("plugin-a", ShellContribution.notice(ShellContribution.Scope.SESSION, "other",
                null, ShellContribution.Severity.INFO, lines("不是我的")));

        assertNull(listener.pollNow());
    }

    @Test
    void shellScopedNotice_should_reachEveryStream() {
        SseContributionListener listener = listener();

        listener.onContribution("plugin-a", ShellContribution.notice(ShellContribution.Scope.SHELL, null,
                null, ShellContribution.Severity.INFO, lines("新插件已加载")));

        assertNotNull(listener.pollNow());
    }

    @Test
    void invalidated_should_becomeShellInvalidatedEvent() {
        SseContributionListener listener = listener();

        listener.onContribution("plugin-a", ShellContribution.invalidated(ShellContribution.Scope.SESSION,
                SESSION, "panel"));

        SseEvent event = listener.pollNow();
        assertNotNull(event);
        assertEquals("shell_invalidated", event.getName());
        ShellInvalidatedEvent payload = (ShellInvalidatedEvent) event.getPayload();
        assertEquals("plugin-a", payload.getOwner());
        assertEquals("panel", payload.getWhat());
    }

    @Test
    void notice_should_filterControlCharacters() {
        // 插件文本里的一个 ESC 序列足以改写客户端终端，因此过滤必须发生在出线之前
        SseContributionListener listener = listener();

        listener.onContribution("plugin-a", ShellContribution.notice(ShellContribution.Scope.SESSION, SESSION,
                null, ShellContribution.Severity.INFO, lines("safe\u001b[31mred\u001b[0m")));

        ShellNoticeEvent payload = (ShellNoticeEvent) listener.pollNow().getPayload();
        assertEquals(1, payload.getLines().size());
        assertTrue(!payload.getLines().get(0).contains("\u001b"),
                "控制字符必须已被滤掉，实际：" + payload.getLines().get(0));
    }

    @Test
    void notice_should_joinSegmentsOfOneLine() {
        SseContributionListener listener = listener();
        List<UiLine> lines = Arrays.asList(
                UiLine.of(UiSegment.of("已索引 "), UiSegment.of("12/40")),
                UiLine.of("第二行"));

        listener.onContribution("plugin-a", ShellContribution.notice(ShellContribution.Scope.SESSION, SESSION,
                null, ShellContribution.Severity.INFO, lines));

        ShellNoticeEvent payload = (ShellNoticeEvent) listener.pollNow().getPayload();
        assertEquals(Arrays.asList("已索引 12/40", "第二行"), payload.getLines());
    }

    @Test
    void notice_should_dropBlankLines_andStaySilentWhenNothingRemains() {
        SseContributionListener listener = listener();

        listener.onContribution("plugin-a", ShellContribution.notice(ShellContribution.Scope.SESSION, SESSION,
                null, ShellContribution.Severity.INFO, Arrays.asList(UiLine.EMPTY, UiLine.of("  "))));

        // 空内容 = 不显示。发一条空事件会让客户端不得不自己定义「空行是什么」，
        // 而那很容易被实现成「清空此前的通知」——一个没人要求的副作用
        assertNull(listener.pollNow());
    }

    @Test
    void pollNow_should_beDrainableInOrder() {
        SseContributionListener listener = listener();

        listener.onContribution("plugin-a", ShellContribution.notice(ShellContribution.Scope.SESSION, SESSION,
                null, ShellContribution.Severity.INFO, lines("第一条")));
        listener.onContribution("plugin-a", ShellContribution.invalidated(ShellContribution.Scope.SESSION,
                SESSION, null));

        assertEquals("shell_notice", listener.pollNow().getName());
        assertEquals("shell_invalidated", listener.pollNow().getName());
        assertNull(listener.pollNow());
    }

    /**
     * 造一个内容行列表。
     *
     * @param texts 行文本
     * @return 行列表
     */
    private static List<UiLine> lines(String... texts) {
        List<UiLine> lines = new java.util.ArrayList<UiLine>();
        for (String text : texts) {
            lines.add(UiLine.of(text));
        }
        return lines;
    }
}
