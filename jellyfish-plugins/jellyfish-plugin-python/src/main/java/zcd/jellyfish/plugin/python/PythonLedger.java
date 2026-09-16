package zcd.jellyfish.plugin.python;

import zcd.jellyfish.script.ScriptGateway;
import zcd.jellyfish.script.ScriptPlugin;
import zcd.jellyfish.script.ScriptRegistration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 已加载脚本的运行态台账：脚本清单、注册结果与启动期问题。
 * <p>
 * <b>为什么需要它</b>：脚本插件在 Java 侧没有自己的 PF4J 身份，注册全部挂在桥接插件的命名空间下，
 * 因此 {@code /plugins} 与插件状态页看不到它们。没有一本台账，「我明明写了脚本」就只能靠翻日志判断——
 * 而清单漂移是本方案唯一的高危点，它的可观测性不该依赖日志。
 * <p>
 * <b>只是一份快照，不是可变容器</b>：记录的是 {@code start()} 那一刻的事实。运行期状态
 * （懒启动 / 运行中 / 熔断）属于将来的网关，塞进这里会让两个生命周期纠缠在一起。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class PythonLedger {

    /** 脚本清单。 */
    private final List<ScriptPlugin> plugins;

    /** 注册结果。 */
    private final List<ScriptRegistration> registrations;

    /** 启动期问题（清单问题与注册问题合并后的可读文本）。 */
    private final List<String> issues;

    /** 脚本运行时；未接通时为 {@code null}。 */
    private final ScriptGateway gateway;

    /**
     * 构造台账。
     *
     * @param plugins       脚本清单，不可为 {@code null}
     * @param registrations 注册结果，不可为 {@code null}
     * @param issues        启动期问题，不可为 {@code null}
     * @param gateway       脚本运行时，可为 {@code null}
     */
    PythonLedger(List<ScriptPlugin> plugins, List<ScriptRegistration> registrations, List<String> issues,
                 ScriptGateway gateway) {
        this.plugins = Collections.unmodifiableList(new ArrayList<ScriptPlugin>(plugins));
        this.registrations = Collections.unmodifiableList(new ArrayList<ScriptRegistration>(registrations));
        this.issues = Collections.unmodifiableList(new ArrayList<String>(issues));
        this.gateway = gateway;
    }

    /**
     * 构造空台账。
     *
     * @return 空台账
     */
    static PythonLedger empty() {
        return new PythonLedger(new ArrayList<ScriptPlugin>(), new ArrayList<ScriptRegistration>(),
                new ArrayList<String>(), null);
    }

    /**
     * 获取脚本清单。
     *
     * @return 不可变列表
     */
    List<ScriptPlugin> plugins() {
        return plugins;
    }

    /**
     * 获取脚本数量。
     *
     * @return 脚本数量
     */
    int scriptCount() {
        return plugins.size();
    }

    /**
     * 获取已登记的能力总数。
     *
     * @return 能力总数
     */
    int capabilityCount() {
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
    List<String> issues() {
        return issues;
    }

    /**
     * 获取日志用的单行汇总。
     *
     * @return 汇总文本，保证非 {@code null}
     */
    String summary() {
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
    String render(String language) {
        StringBuilder builder = new StringBuilder();
        builder.append(language).append("：脚本 ").append(plugins.size())
                .append(" 个，已登记能力 ").append(capabilityCount()).append(" 项");
        if (gateway != null) {
            // 运行态单独一行：它回答的是另一个问题——「进程侧现在到底在干什么」。
            // 与注册数放在同一行会让人以为它们是一回事，而这两者**本来就是解耦的**：
            // 进程没起，注册照样有效；这正是这套设计最需要被看见的一点
            builder.append("\n运行时：").append(gateway.describe());
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
