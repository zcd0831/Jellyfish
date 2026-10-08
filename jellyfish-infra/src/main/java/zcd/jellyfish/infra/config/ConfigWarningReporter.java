package zcd.jellyfish.infra.config;

import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.support.ControlChars;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置告警的「给人看」那条出口：把 {@link ConfigWarningEvent} 打成一行 WARN 日志。
 * <p>
 * <b>为什么必须有它</b>：配置层的告警全部只发事件（见 {@link ConfigWarningEvent} 的 javadoc：
 * 呈现方式由订阅方决定），而此前唯一的订阅方是 {@code MetricsSubscriber}——它只把告警
 * <b>计数</b>。于是「配置文件缺失」「项目级配置未信任」「配置里有内核不认识的字段」这些提示
 * 事实上都到不了用户眼前：现场表现是配置写了没作用，而关停时那句
 * {@code [计数] config.warnings=N} 只说数量、不说是什么。<b>一条没人看得见的告警等于没有</b>。
 * <p>
 * <b>为什么是新组件，而不是在配置层里直接打日志</b>：配置层不依赖日志实现这条分工要保住——
 * 它保证同一批告警能被完全不同的观察者消费（指标、插件、脚本、日志），也保证单元测试不必去捞日志。
 * 因此这里只做一件事：订阅、渲染。它<b>不发布任何事件</b>（否则「事件 → 日志 → 事件」会自激）。
 * <p>
 * <b>文本先过 {@link ControlChars#singleLine}</b>：告警里带着配置文件的路径与<b>字段名</b>，
 * 而字段名来自用户的 JSON——一个带 {@code \u001b[2J} 或换行的键名可以在终端里清屏、或伪造出一整行
 * 「已授权」这类诊断。这与 CLI 诊断行是同一条纪律（见 SEC-17），判据是「文本从哪来」，不是「写到哪去」。
 *
 * @author zcd
 */
@Singleton
public final class ConfigWarningReporter implements AutoCloseable {

    /** 订阅来源标识：统一用它，便于整体回收。 */
    private static final String OWNER = "config-warnings";

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ConfigWarningReporter.class);

    /** 事件通道：唯一的数据来源。 */
    private final EventChannel events;

    /** 已建立的订阅句柄，关闭时解除。 */
    private final List<Subscription> subscriptions = new ArrayList<Subscription>();

    /** 是否已启动：{@link #start()} 与 {@link #close()} 都幂等。 */
    private boolean started;

    /**
     * 构造订阅者。
     *
     * @param events 事件通道，不可为 {@code null}
     */
    @Inject
    public ConfigWarningReporter(EventChannel events) {
        this.events = events;
    }

    /**
     * 开始上报：订阅配置告警。幂等。
     * <p>
     * 必须在 {@code eventChannel.start()} 之后、配置加载之前调用：之后才注册得上订阅，
     * 而配置加载阶段发出的告警（正是绝大多数）要能被看到。
     */
    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        subscriptions.add(events.subscribe(OWNER, ConfigWarningEvent.class, this::report));
    }

    /**
     * 停止上报：解除订阅。幂等。
     * <p>
     * 只解除自己那一份，不碰通道上别人的订阅。
     */
    @Override
    public synchronized void close() {
        for (Subscription subscription : subscriptions) {
            subscription.close();
        }
        subscriptions.clear();
        started = false;
    }

    /**
     * 把一条配置告警渲染成一行日志。
     *
     * @param event 配置告警事件
     */
    private void report(ConfigWarningEvent event) {
        LOG.warn("{}", text(event));
    }

    /**
     * 渲染告警文本：{@code 配置告警（来源）：消息}，来源缺失时省掉括号那一段。
     * <p>
     * <b>两段文本都要过 {@link ControlChars#singleLine}</b>：来源是文件路径（可能由配置指定），
     * 消息里带着 JSON 的<b>字段名</b>——两者都是用户可控的文本，而这里的输出会落到终端（{@code -cli}
     * 的 stderr）与日志文件。带 {@code \u001b[2J} 的键名能清屏，带 {@code \n} 的键名能伪造出一整行
     * 「已授权」这类诊断。判据是「文本从哪来」，与写到哪去无关。
     *
     * @param event 配置告警事件
     * @return 单行文本，保证非 {@code null}
     */
    static String text(ConfigWarningEvent event) {
        String message = ControlChars.singleLine(event.getMessage());
        String source = ControlChars.singleLine(event.getSource());
        if (source == null || source.trim().isEmpty()) {
            return "配置告警：" + message;
        }
        return "配置告警（" + source.trim() + "）：" + message;
    }
}
