package zcd.jellyfish.server;

import zcd.jellyfish.api.extension.ShellContribution;
import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.core.conversation.ShellContributionListener;
import zcd.jellyfish.infra.support.ControlChars;
import zcd.jellyfish.server.dto.ShellInvalidatedEvent;
import zcd.jellyfish.server.dto.ShellNoticeEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 把尽力 lane 上的插件贡献翻译成 SSE 事件并投进队列。
 * <p>
 * <b>与 {@link SseTurnListener} 的关系</b>：同一个角色、不同的 lane。可靠 lane 的事件到
 * {@link SseTurnListener}，插件贡献到这里，两者都由 {@link zcd.jellyfish.server.handler.ChatHandler}
 * 的写循环取走——写 socket 只有一个写者这条纪律不因为多了一条 lane 而改变。
 * <p>
 * <b>它按会话过滤</b>：{@code SESSION} scope 的贡献只投给它自己的那条流，
 * 否则并发客户端会互相看到对方的通知。{@code SHELL} scope 是进程级事实，因此发给每一条流。
 * <p>
 * <b>队列无界，但这不构成风险</b>：贡献在进这里之前已经过每 owner 有界信箱的裁剪，
 * 而本队列的消费者（写线程）始终在跑，因此它不会比那个信箱更大。
 * <p>
 * <b>控制字符在这里滤掉</b>：文本来自插件，客户端不一定会自己滤（见 {@link ShellNoticeEvent}）。
 * <p>
 * 线程安全：队列自带并发语义。
 *
 * @author zcd
 */
public final class SseContributionListener implements ShellContributionListener {

    /** 待写出的 SSE 事件队列。 */
    private final BlockingQueue<SseEvent> queue = new LinkedBlockingQueue<SseEvent>();

    /** 本流对应的会话标识。 */
    private final String sessionId;

    /**
     * 构造监听器。
     *
     * @param sessionId 会话标识，不可为空白
     */
    public SseContributionListener(String sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * 立即取一条事件（不等待），供写循环与测试使用。
     *
     * @return 事件；队列为空时返回 {@code null}
     */
    public SseEvent pollNow() {
        return queue.poll();
    }

    @Override
    public void onContribution(String owner, ShellContribution contribution) {
        if (!appliesTo(contribution)) {
            return;
        }
        if (contribution.getKind() == ShellContribution.Kind.INVALIDATED) {
            queue.offer(new SseEvent("shell_invalidated",
                    new ShellInvalidatedEvent(owner, contribution.getSessionId(), contribution.getWhat())));
            return;
        }
        List<String> lines = textsOf(contribution);
        if (lines.isEmpty()) {
            // 空内容 = 不显示；不发一条空事件，否则客户端要自己判断「空行是什么」，
            // 而那个判断很容易被误实现成「清空此前的通知」
            return;
        }
        queue.offer(new SseEvent("shell_notice", new ShellNoticeEvent(owner, contribution.getSessionId(),
                contribution.getKey(), contribution.getSeverity().name(), lines)));
    }

    /**
     * 判断一条贡献是否该出现在本流上。
     * <p>
     * <b>{@code SHELL} scope 发给每一条流</b>：它是进程级事实（例如「一个新插件刚加载」），
     * 与某个会话无关，任何客户端都该看到。{@code SESSION} scope 则必须精确匹配。
     *
     * @param contribution 贡献
     * @return 该出现在本流上返回 {@code true}
     */
    private boolean appliesTo(ShellContribution contribution) {
        if (contribution.getScope() == ShellContribution.Scope.SHELL) {
            return true;
        }
        return sessionId.equals(contribution.getSessionId());
    }

    /**
     * 取通知的文本行，逐行滤掉控制字符。
     *
     * @param contribution 通知贡献
     * @return 文本行，无可用内容时为空列表
     */
    private static List<String> textsOf(ShellContribution contribution) {
        List<String> lines = new ArrayList<String>();
        for (UiLine line : contribution.getLines()) {
            String filtered = ControlChars.strip(line.text());
            // 全空白的行也是空行：留着它只会让客户端画出一次空转义与一次多余的换行
            if (filtered == null || filtered.trim().isEmpty()) {
                continue;
            }
            lines.add(filtered);
        }
        return lines.isEmpty() ? Collections.<String>emptyList() : lines;
    }
}
