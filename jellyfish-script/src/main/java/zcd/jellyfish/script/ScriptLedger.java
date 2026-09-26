package zcd.jellyfish.script;

import zcd.jellyfish.script.event.ScriptEventBridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 已加载脚本的运行态台账：脚本清单、注册结果与启动期问题。
 * <p>
 * <b>为什么需要它</b>：脚本插件在 Java 侧没有自己的 PF4J 身份，注册全部挂在桥接插件的命名空间下，
 * 因此 {@code /plugins} 与插件状态页看不到它们。没有一本台账，「我明明写了脚本」就只能靠翻日志判断——
 * 而清单漂移是本方案唯一的高危点，它的可观测性不该依赖日志。
 * <p>
 * <b>它是一份快照，而不是可变容器</b>：注册结果与启动期问题是 {@code start()} 那一刻的事实，
 * 不随后续变化。但运行态（进程在不在、哪些脚本被熔断了）必然是<b>活的</b>，
 * 因此这两部分不是存下来的副本，而是转发到运行时去看一眼。
 * 区分方式是：报告「注册了什么」用快照，报告「现在怎么样」用视图——
 * 把后者也快照下来，会得到一份「已经过时的真相」，而它比没有更具误导性。
 * <p>
 * <b>它与语言无关</b>：语言名只是渲染时传进来的一个字符串，
 * 因此同一份台账同时服务所有桥接插件。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ScriptLedger {

    /** 脚本清单。 */
    private final List<ScriptPlugin> plugins;

    /** 注册结果。 */
    private final List<ScriptRegistration> registrations;

    /** 启动期问题（清单问题与注册问题合并后的可读文本）。 */
    private final List<String> issues;

    /** 脚本运行时；未接通时为 {@code null}。 */
    private final ScriptGateway gateway;

    /** 带熔断的调用入口；未接通时为 {@code null}。 */
    private final CircuitBreakingScriptCaller callers;

    /** 事件桥接，可为 {@code null}（未接通时台账不报事件行）。 */
    private final ScriptEventBridge events;

    /**
     * 构造台账。
     *
     * @param plugins       脚本清单，不可为 {@code null}
     * @param registrations 注册结果，不可为 {@code null}
     * @param issues        启动期问题，不可为 {@code null}
     * @param gateway       脚本运行时，可为 {@code null}
     * @param callers       带熔断的调用入口，可为 {@code null}
     * @param events        事件桥接，可为 {@code null}
     */
    public ScriptLedger(List<ScriptPlugin> plugins, List<ScriptRegistration> registrations,
                        List<String> issues, ScriptGateway gateway,
                        CircuitBreakingScriptCaller callers, ScriptEventBridge events) {
        this.plugins = Collections.unmodifiableList(new ArrayList<ScriptPlugin>(plugins));
        this.registrations = Collections.unmodifiableList(new ArrayList<ScriptRegistration>(registrations));
        this.issues = Collections.unmodifiableList(new ArrayList<String>(issues));
        this.gateway = gateway;
        this.callers = callers;
        this.events = events;
    }

    /**
     * 构造空台账。
     *
     * @return 空台账
     */
    public static ScriptLedger empty() {
        return new ScriptLedger(new ArrayList<ScriptPlugin>(), new ArrayList<ScriptRegistration>(),
                new ArrayList<String>(), null, null, null);
    }

    /**
     * 获取脚本清单。
     *
     * @return 不可变列表
     */
    public List<ScriptPlugin> plugins() {
        return plugins;
    }

    /**
     * 获取脚本数量。
     *
     * @return 脚本数量
     */
    public int scriptCount() {
        return plugins.size();
    }

    /**
     * 获取已登记的能力总数。
     *
     * @return 能力总数
     */
    public int capabilityCount() {
        int total = 0;
        for (ScriptRegistration registration : registrations) {
            total += registration.registeredCount();
        }
        return total;
    }

    /**
     * 获取启动期问题。
     *
     * @return 不可变问题清单
     */
    public List<String> issues() {
        return issues;
    }

    /**
     * 获取日志用的单行汇总。
     *
     * @return 汇总文本，保证非 {@code null}
     */
    public String summary() {
        return "scripts=" + plugins.size() + " capabilities=" + capabilityCount()
                + " issues=" + issues.size();
    }

    /**
     * 渲染成给人看的清单，供 {@code /<语言>} 命令输出。
     * <p>
     * 问题放在末尾逐条列出而不是只报一个数量：作者拿到这份输出就该能直接动手改，
     * 「有 3 个问题」而不说什么问题，等于把定位工作又推回去。
     *
     * @param language 语言名，用于开头一行
     * @return 文本，保证非 {@code null}
     */
    public String render(String language) {
        StringBuilder builder = new StringBuilder();
        builder.append(language).append("：脚本 ").append(plugins.size())
                .append(" 个，已登记能力 ").append(capabilityCount()).append(" 项");
        if (gateway != null) {
            // 运行态单独一行：它回答的是另一个问题——「进程侧现在到底在干什么」。
            // 与注册数放在同一行会让人以为它们是一回事，而这两者**本来就是解耦的**：
            // 进程没起，注册照样有效；这正是这套设计最需要被看见的一点
            builder.append("\n运行时：").append(gateway.describe());
        }
        if (callers != null) {
            // 熔断态也单独一行：它回答的是「哪些脚本被暂时拒绝了，还要等多久」。
            // 只列有记录的脚本——从未调用过的脚本没有账可报
            Map<String, String> states = callers.states();
            builder.append("\n熔断：");
            if (states.isEmpty()) {
                builder.append("尚未发生调用");
            } else {
                boolean first = true;
                for (Map.Entry<String, String> entry : states.entrySet()) {
                    if (!first) {
                        builder.append("；");
                    }
                    first = false;
                    builder.append(entry.getKey()).append(' ').append(entry.getValue());
                }
            }
        }
        if (events != null) {
            // 事件行同样单独一行：它回答的是「脚本和内核之间的事件通道通不通、丢了多少」。
            // 「推送 0」在没订阅任何事件的部署里是正常的，所以这里不解释、只报数
            builder.append("\n").append(events.describe());
        }
        if (plugins.isEmpty()) {
            builder.append("\n（脚本目录为空，或清单都不可用）");
        }
        for (ScriptRegistration registration : registrations) {
            builder.append("\n  ").append(registration.scriptId()).append(": ")
                    .append(registration.registeredCount()).append(" 项");
        }
        if (!issues.isEmpty()) {
            builder.append("\n问题 ").append(issues.size()).append(" 条：");
            for (String issue : issues) {
                builder.append("\n  ! ").append(issue);
            }
        }
        return builder.toString();
    }
}
