package zcd.jellyfish.core.conversation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.extension.ShellContribution;
import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.infra.metrics.MetricsRegistry;
import zcd.jellyfish.infra.shell.ShellIngress;
import zcd.jellyfish.core.ReActListener;
import zcd.jellyfish.core.ReActResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShellStreams} 的订阅、扇出与异常隔离，以及 {@code ReActListener → ShellTurnEvent} 的翻译。
 *
 * @author zcd
 */
class ShellStreamsTest {

    private ShellStreams streams;

    /** 与门面共享的信箱，测试从这里投递插件贡献。 */
    private ShellIngress ingress;

    @BeforeEach
    void setUp() {
        ingress = new ShellIngress(new MetricsRegistry());
        streams = new ShellStreams(ingress);
    }

    @Test
    void subscribe_should_deliver_only_events_of_that_session() {
        List<ShellTurnEvent> received = new ArrayList<ShellTurnEvent>();
        streams.subscribe("s1", received::add);

        streams.publish(ShellTurnEvent.text("s1", "t1", "a"));
        streams.publish(ShellTurnEvent.text("s2", "t2", "b"));

        assertEquals(1, received.size());
        assertEquals("a", received.get(0).getText());
    }

    @Test
    void subscribeAll_should_deliver_every_session() {
        List<ShellTurnEvent> received = new ArrayList<ShellTurnEvent>();
        streams.subscribeAll(received::add);

        streams.publish(ShellTurnEvent.text("s1", "t1", "a"));
        streams.publish(ShellTurnEvent.text("s2", "t2", "b"));

        assertEquals(2, received.size());
    }

    @Test
    void subscribeAll_and_session_subscriber_should_each_get_one_copy() {
        List<ShellTurnEvent> bySession = new ArrayList<ShellTurnEvent>();
        List<ShellTurnEvent> all = new ArrayList<ShellTurnEvent>();
        streams.subscribe("s1", bySession::add);
        streams.subscribeAll(all::add);

        streams.publish(ShellTurnEvent.text("s1", "t1", "a"));

        assertEquals(1, bySession.size());
        assertEquals(1, all.size());
    }

    @Test
    void close_should_stop_delivery() {
        List<ShellTurnEvent> received = new ArrayList<ShellTurnEvent>();
        Subscription subscription = streams.subscribe("s1", received::add);

        streams.publish(ShellTurnEvent.text("s1", "t1", "a"));
        subscription.close();
        streams.publish(ShellTurnEvent.text("s1", "t1", "b"));

        assertEquals(1, received.size());
    }

    @Test
    void close_should_be_idempotent() {
        Subscription subscription = streams.subscribe("s1", event -> {
        });
        subscription.close();
        subscription.close();
    }

    @Test
    void publish_should_isolate_failing_subscriber_from_others() {
        List<ShellTurnEvent> received = new ArrayList<ShellTurnEvent>();
        streams.subscribe("s1", event -> {
            throw new JellyfishException("broken subscriber");
        });
        streams.subscribe("s1", received::add);

        streams.publish(ShellTurnEvent.text("s1", "t1", "a"));

        assertEquals(1, received.size());
    }

    @Test
    void publish_should_tolerate_no_subscribers() {
        streams.publish(ShellTurnEvent.text("s1", "t1", "a"));
    }

    @Test
    void subscribe_should_reject_blank_session() {
        assertThrows(JellyfishException.class, () -> streams.subscribe(null, event -> {
        }));
        assertThrows(JellyfishException.class, () -> streams.subscribe("  ", event -> {
        }));
    }

    @Test
    void publisher_should_translate_every_callback() {
        List<ShellTurnEvent> received = new ArrayList<ShellTurnEvent>();
        streams.subscribe("s1", received::add);
        ReActListener publisher = streams.publisher("s1", "t1");

        publisher.onText("a");
        publisher.onThinking("b");
        publisher.onToolCallStarted("c1", "bash");
        publisher.onToolCallOutput("c1", "bash", "out");
        publisher.onToolCallCompleted("c1", "bash", true, "ok",
                java.util.Collections.<String, Object>singletonMap("exitCode", 0));
        publisher.onComplete(ReActResult.completed("s1", "done", 3));

        assertEquals(6, received.size());
        assertEquals(ShellTurnEvent.Kind.TEXT, received.get(0).getKind());
        assertEquals(ShellTurnEvent.Kind.THINKING, received.get(1).getKind());
        assertEquals(ShellTurnEvent.Kind.TOOL_STARTED, received.get(2).getKind());
        assertEquals("bash", received.get(2).getToolName());
        assertEquals(ShellTurnEvent.Kind.TOOL_OUTPUT, received.get(3).getKind());
        assertEquals(ShellTurnEvent.Kind.TOOL_COMPLETED, received.get(4).getKind());
        assertTrue(received.get(4).isSuccess());
        assertEquals(ShellTurnEvent.Kind.COMPLETED, received.get(5).getKind());
        assertEquals(3, received.get(5).getRounds());
        assertTrue(received.get(5).isTerminal());
    }

    @Test
    void publisher_should_skip_empty_deltas() {
        List<ShellTurnEvent> received = new ArrayList<ShellTurnEvent>();
        streams.subscribe("s1", received::add);
        ReActListener publisher = streams.publisher("s1", "t1");

        publisher.onText("");
        publisher.onText(null);
        publisher.onThinking("");
        publisher.onToolCallOutput("c1", "bash", "");

        assertTrue(received.isEmpty());
    }

    @Test
    void publisher_should_keep_turn_id_and_session_on_every_event() {
        List<ShellTurnEvent> received = new ArrayList<ShellTurnEvent>();
        streams.subscribe("s1", received::add);
        ReActListener publisher = streams.publisher("s1", "t1");

        publisher.onToolCallStarted("c1", "bash", java.util.Collections.<String, Object>emptyMap());
        publisher.onCancelled();
        publisher.onBlocked("blocked");
        publisher.onError(new JellyfishException("boom"));

        for (ShellTurnEvent event : received) {
            assertEquals("s1", event.getSessionId());
            assertEquals("t1", event.getTurnId());
        }
        assertEquals(ShellTurnEvent.Kind.TOOL_STARTED, received.get(0).getKind());
        assertEquals(ShellTurnEvent.Kind.CANCELLED, received.get(1).getKind());
        assertEquals(ShellTurnEvent.Kind.BLOCKED, received.get(2).getKind());
        assertEquals("blocked", received.get(2).getReason());
        assertEquals(ShellTurnEvent.Kind.ERROR, received.get(3).getKind());
    }

    @Test
    void publisher_should_expose_tool_arguments_on_start() {
        List<ShellTurnEvent> received = new ArrayList<ShellTurnEvent>();
        streams.subscribe("s1", received::add);

        streams.publisher("s1", "t1").onToolCallStarted("c1", "bash",
                java.util.Collections.<String, Object>singletonMap("command", "ls"));

        Map<String, Object> arguments = received.get(0).getToolArguments();
        assertEquals("ls", arguments.get("command"));
        assertThrows(UnsupportedOperationException.class, () -> arguments.put("x", "y"));
    }

    @Test
    void drainShell_should_deliverPendingContributionsToEverySubscriber() {
        List<String> seen = new ArrayList<String>();
        streams.subscribeShell((owner, contribution) -> seen.add(owner + ":" + text(contribution)));
        streams.subscribeShell((owner, contribution) -> seen.add("second:" + text(contribution)));
        ingress.present("plugin-a", notice("hello"));

        assertEquals(1, streams.drainShell());

        assertEquals(2, seen.size());
        assertEquals("plugin-a:hello", seen.get(0));
        assertEquals("second:hello", seen.get(1));
    }

    @Test
    void drainShell_should_beIdempotentBeforeNewContributions() {
        streams.subscribeShell((owner, contribution) -> {
        });
        ingress.present("plugin-a", notice("hello"));

        assertEquals(1, streams.drainShell());
        assertEquals(0, streams.drainShell());
    }

    @Test
    void drainShell_should_isolateFailingSubscriber() {
        List<String> seen = new ArrayList<String>();
        streams.subscribeShell((owner, contribution) -> {
            throw new JellyfishException("broken shell subscriber");
        });
        streams.subscribeShell((owner, contribution) -> seen.add(text(contribution)));
        ingress.present("plugin-a", notice("hello"));

        streams.drainShell();

        assertEquals(1, seen.size());
    }

    @Test
    void drainShell_should_discardEntriesWhenNobodySubscribed() {
        // 没有订阅者时照样把信箱清空：否则一个不会来取的外壳会让队列一直积压
        ingress.present("plugin-a", notice("hello"));

        assertEquals(0, streams.drainShell());
        assertEquals(0, ingress.drain().size());
    }

    @Test
    void subscribeShell_should_stopDeliveringAfterClose() {
        List<String> seen = new ArrayList<String>();
        Subscription subscription = streams.subscribeShell((owner, contribution) -> seen.add(text(contribution)));
        ingress.present("plugin-a", notice("first"));
        streams.drainShell();

        subscription.close();
        ingress.present("plugin-a", notice("second"));
        streams.drainShell();

        assertEquals(1, seen.size());
    }

    @Test
    void subscribeShell_should_rejectNullListener() {
        assertThrows(NullPointerException.class, () -> streams.subscribeShell(null));
    }

    /**
     * 取自一条通知的首行文本。
     *
     * @param contribution 贡献
     * @return 文本
     */
    private static String text(ShellContribution contribution) {
        return contribution.getLines().get(0).text();
    }

    /**
     * 造一条无 key 通知。
     *
     * @param text 文本
     * @return 贡献
     */
    private static ShellContribution notice(String text) {
        return ShellContribution.notice(ShellContribution.Scope.SHELL, null, null,
                ShellContribution.Severity.INFO, Collections.singletonList(UiLine.of(text)));
    }
}
