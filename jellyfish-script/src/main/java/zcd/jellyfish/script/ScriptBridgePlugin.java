package zcd.jellyfish.script;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CommandDescriptor;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.plugin.JellyfishPlugin;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.script.codec.ExtensionCodecs;
import zcd.jellyfish.script.event.ScriptEventBridge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 桥接插件的骨架：内核眼里的一个标准 PF4J 插件，背后是某一门语言的脚本插件。
 * <p>
 * <b>它为什么必须存在</b>：内核的扩展边界由三条硬性质定义，全部发生在 JVM 内——插件身份
 * （注册表的 {@code owner}）来自 PF4J 描述符、注册窗口只允许在 {@code start(PluginContext)} 内、
 * 能力只能从 {@code PluginContext} 的几个方法拿到。脚本进程是 JVM 外的东西，既没有 PF4J 身份，
 * 也拿不到 {@code PluginContext}（那是 JVM 对象，跨不过进程边界）。因此必须有一个 JVM 内的代理人，
 * 在 {@code start()} 里替脚本调用 {@code handle} / {@code contribute} / {@code observe} / {@code emit}。
 * <p>
 * <b>为什么它在机制层而不是在某个桥接插件里</b>：这一段流程（探测解释器、扫描清单、逐脚本注册、
 * 接通事件桥接与熔断、注册 {@code /<语言>} 命令、按序关闭）没有一处与语言有关，
 * 与语言有关的只有两件事：怎么从配置段里读出解释器、以及怎么造出语言适配。
 * 把它们抽成两个抽象方法之后，新增一门语言只需要一个薄子类；否则每门语言都要复制上千行
 * 几乎逐字相同的代理代码，而那种复制会以「修了一门语言忘了另一门」的形式持续收费。
 * <p>
 * <b>注册与进程生命周期解耦</b>：脚本的注册来自脚本目录下的静态清单 {@code manifest.json}，
 * 因此在 {@code start()} 里就能完成全部注册，<b>不需要拉起任何解释器进程</b>。
 * 好处有三：语言环境缺失不影响内核启动且工具清单依然完整；没有请求时脚本侧进程数为零；
 * worker 崩溃后可直接重建，不必重新注册。
 * <p>
 * <b>每个脚本一个 owner</b>：注册走 {@link PluginContext#subContext(String)}，落在
 * {@code <pluginId>::<脚本标识>} 下。诊断输出因此能指出「这个工具是哪个脚本提供的」，
 * 而框架卸载时按命名空间一次性把整门语言的注册收干净——两者同时成立，不矛盾。
 * <p>
 * <b>能力上下文每次 start 现造</b>（PF4J 会长期缓存插件实例，{@code stop} 不丢弃它），
 * 因此本类持有的状态同样在 {@code start()} 现造、{@code stop()} 释放，保证「重启插件」真的能读到新配置。
 * 子类因此<b>不得</b>覆盖 {@link #start(PluginContext)} / {@link #stop()}：破坏这条性质的方式
 * 不止一种，而它的现场表现（新配置不生效）与配置系统本身的问题长得一模一样。
 *
 * @author zcd
 */
public abstract class ScriptBridgePlugin implements JellyfishPlugin {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ScriptBridgePlugin.class);

    /** 解释器探测的等待上限：探测只为「告警一句」，不该拖慢内核启动。 */
    private static final long PROBE_TIMEOUT_SECONDS = 2L;

    /** 语言适配，在 {@code start()} 现造、{@code stop()} 释放。 */
    private ScriptLanguage language;

    /** 本语言的有效配置，在 {@code start()} 现造、{@code stop()} 释放。 */
    private ScriptBridgeConfig config;

    /** 脚本运行时，在 {@code start()} 现造、{@code stop()} 释放。 */
    private ScriptGateway gateway;

    /** 带熔断的调用入口，在 {@code start()} 现造、{@code stop()} 释放。 */
    private CircuitBreakingScriptCaller caller;

    /** 脚本台账，在 {@code start()} 现造、{@code stop()} 释放。 */
    private ScriptLedger ledger = ScriptLedger.empty();

    /** 事件桥接：内核事件推给脚本、脚本发布的事件代为发布。 */
    private ScriptEventBridge events;

    /**
     * 解析本语言的配置。
     * <p>
     * 子类的实现通常只有一行：{@code ScriptBridgeConfig.from(configuration, "nodePath", "node",
     * "scripts/node")}——即「解释器写在哪一个键上、以及两个默认值」。
     *
     * @param configuration 插件配置段，可为 {@code null}
     * @return 配置值对象
     * @throws JellyfishException 配置值类型不对时抛出
     */
    protected abstract ScriptBridgeConfig resolveConfig(Map<String, Object> configuration);

    /**
     * 用配置造出本语言的语言适配。
     * <p>
     * 每次 {@code start()} 都会调用一次，因此不要返回缓存的实例：
     * 语言适配里带着解释器路径与资源清单，而这两样都可能被配置改掉。
     *
     * @param config 刚解析出来的配置，不可为 {@code null}
     * @return 语言适配，不可为 {@code null}
     */
    protected abstract ScriptLanguage createLanguage(ScriptBridgeConfig config);

    /**
     * 启动插件：解析配置、扫描清单、逐脚本注册转发处理器。
     * <p>
     * <b>不做的事</b>：不拉起解释器进程、不 import 用户脚本、不读脚本正文。这三件事都会把
     * 「这门语言的环境是否健全」变成内核能否启动的前提，而本方案刻意让它们解耦。
     * <p>
     * <b>失败语义</b>：单个脚本的清单问题、某个条目的注册冲突都只记入台账并继续；
     * 只有「脚本根目录存在但不是可读目录」这类全局故障才抛出，使插件转 {@code FAILED}。
     *
     * @param context 能力上下文，由框架创建，不可为 {@code null}
     * @throws JellyfishException 脚本根目录不可读时抛出
     */
    @Override
    public final void start(PluginContext context) {
        config = resolveConfig(context.configuration());
        language = createLanguage(config);
        probeInterpreter();
        ledger = loadScripts(context);
        registerStatusCommand(context);
        LOG.info("{} 桥接插件已启动: pluginId={} scriptsRoot={} {}", language.displayName(),
                context.pluginId(), config.scriptsRoot(), ledger.summary());
        for (String issue : ledger.issues()) {
            LOG.error("脚本问题: {}", issue);
        }
    }

    /**
     * 停止插件：关闭脚本运行时并释放全部状态。
     * <p>
     * 注册由框架按 owner 命名空间回收，这里只释放自己的引用；顺序上必须先关运行时，
     * 它负责把子进程（网关与全部 worker）请走。
     */
    @Override
    public final void stop() {
        if (language == null) {
            return;
        }
        LOG.info("{} 桥接插件已停止: {} {}", language.displayName(), language, ledger.summary());
        if (gateway != null) {
            gateway.close();
        }
        language = null;
        config = null;
        gateway = null;
        caller = null;
        events = null;
        ledger = ScriptLedger.empty();
    }

    /**
     * 获取当前语言适配，供测试断言「start 之后确实建立了适配、stop 之后确实释放了」。
     *
     * @return 语言适配；未启动时为 {@code null}
     */
    public ScriptLanguage language() {
        return language;
    }

    /**
     * 获取当前有效配置，供测试与诊断确认「配置真的被读进来了」。
     *
     * @return 配置；未启动时为 {@code null}
     */
    public ScriptBridgeConfig config() {
        return config;
    }

    /**
     * 获取脚本台账，供测试与诊断使用。
     *
     * @return 台账，保证非 {@code null}
     */
    public ScriptLedger ledger() {
        return ledger;
    }

    /**
     * 获取脚本运行时，供测试断言生命周期。
     *
     * @return 脚本运行时；未启动时为 {@code null}
     */
    public ScriptGateway gateway() {
        return gateway;
    }

    /**
     * 获取带熔断的调用入口，供测试断言「每一个脚本调用都真的经过熔断」。
     *
     * @return 调用入口；未启动时为 {@code null}
     */
    public CircuitBreakingScriptCaller caller() {
        return caller;
    }

    /**
     * 扫描脚本目录并逐脚本注册。
     * <p>
     * 运行时在这里创建、但不启动任何进程：抽取网关资源、fork worker 都留到第一次真正调用，
     * 因此「解释器没装」或「主目录不可写」都不会影响插件加载，也不会影响工具清单的完整性。
     *
     * @param context 能力上下文
     * @return 台账
     */
    private ScriptLedger loadScripts(PluginContext context) {
        ScriptScanResult scan = new ScriptPluginScanner(ExtensionCodecs.DEFAULTS).scan(config.scriptsRoot());
        List<String> issues = new ArrayList<String>();
        for (ScriptIssue issue : scan.issues()) {
            issues.add("清单: " + issue);
        }
        GatewaySettings settings = config.gatewaySettings(language.id());
        gateway = ScriptGateway.builder(language)
                .resources(new GatewayResources(config.gatewayRoot()))
                .settings(settings)
                .scripts(scan.plugins())
                .build();
        // 转发闭包拿到的就是这个带熔断的入口：因此「拒绝派发」发生在注册好的处理器内部，
        // 而**不需要把注册摘掉**——工具仍在清单里，模型看到的是一条带剩余时间的错误
        events = new ScriptEventBridge(context, language.displayName(), gateway, settings.allowedEvents());
        // 事件桥接要用网关推送，而网关要先存在，因此这里是「构造后注册」而不是注入
        gateway.eventSink(events);
        events.start();
        caller = new CircuitBreakingScriptCaller(gateway, config.circuitBreakerSettings(),
                new CircuitWarningPublisher(context));
        ScriptRegistrar registrar = new ScriptRegistrar(ExtensionCodecs.DEFAULTS, caller);
        List<ScriptRegistration> registrations = new ArrayList<ScriptRegistration>();
        for (ScriptPlugin plugin : scan.plugins()) {
            registrations.add(registrar.register(context.subContext(plugin.id()), plugin));
        }
        return new ScriptLedger(scan.plugins(), registrations, issues, gateway, caller, events);
    }

    /**
     * 探测解释器是否存在，失败只告警。
     * <p>
     * <b>为什么只告警不让插件失败</b>：工具清单来自清单文件、与解释器无关，因此解释器缺失时
     * 内核完全可以正常启动并把工具列出来。若在这里让插件转 {@code FAILED}，用户看到的是
     * 「我配好的工具凭空消失了」，而真正的原因（这台机器上没有解释器）只在日志里。
     * <p>
     * 探测本身也有超时：解释器可能是用户自己写的包装脚本，卡住它不该拖住内核启动。
     */
    private void probeInterpreter() {
        List<String> command = language.probeCommand();
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOG.warn("解释器探测超时（{}s），已跳过: {}", Long.valueOf(PROBE_TIMEOUT_SECONDS), command);
                return;
            }
            if (process.exitValue() != 0) {
                LOG.warn("解释器探测返回非零退出码 {}: {}", Integer.valueOf(process.exitValue()), command);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("解释器探测被中断: {}", command);
        } catch (IOException e) {
            LOG.warn("未找到解释器 {}，脚本工具会在第一次调用时失败（清单仍完整、内核不受影响）: {}",
                    language.id(), e.getMessage());
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /**
     * 注册 {@code /<语言>} 状态命令。
     * <p>
     * <b>为什么桥接插件需要自带一条命令</b>：脚本插件在 Java 侧没有 PF4J 身份，
     * {@code /plugins} 看不到它们；而「清单写错了」这类问题的现场特征恰恰是「工具没出现」，
     * 那是最需要可观测性的时候。这条命令注册在自己的 pluginId 下——它属于桥接插件本身，
     * 不属于任何脚本，因此不进脚本命名空间。
     *
     * @param context 能力上下文
     */
    private void registerStatusCommand(PluginContext context) {
        context.handle(CommandRequest.class, language.id(),
                // 末尾显式声明 sessionRequired=false：台账只报加载与注册情况，跟会话无关
                new CommandDescriptor("查看 " + language.displayName() + " 脚本插件的加载与注册情况",
                        null, null, false),
                request -> CommandResult.ok(ledger.render(language.displayName())));
    }

    /**
     * 把熔断状态变化发成进程级告警。
     * <p>
     * <b>为什么是事件而不是日志</b>：熔断的直接后果是「模型开始拿到一个奇怪的失败」，
     * 而这条线索只有调用点看得见。发成事件后，日志、指标、TUI 都能各自决定怎么呈现，
     * 而桥接插件不必知道谁来读。
     * <p>
     * <b>为什么只在转移时发</b>：熔断期间每次调用都失败，每次都发一条会把「打开了」这件事
     * 淹没在噪声里；而它本来只需被看见一次。恢复也发一条——否则日志里留下的只有
     * 「某个工具坏了」，而它其实已经好了。
     * <p>
     * 告警来源取自 {@code pluginId} 而不是写死的配置段名：两者必须一致才指向用户在
     * {@code jellyfish.json} 里写的那一段，而「插件标识」这件事 PF4J 已经知道答案了。
     */
    private static final class CircuitWarningPublisher implements ScriptCircuitListener {

        /** 能力上下文，用于发布事件。 */
        private final PluginContext context;

        /** 告警来源：本插件的配置段。 */
        private final String source;

        /**
         * 构造告警发布者。
         *
         * @param context 能力上下文
         */
        private CircuitWarningPublisher(PluginContext context) {
            this.context = context;
            this.source = "plugins.configurations." + context.pluginId();
        }

        @Override
        public void onOpened(String scriptId, ScriptCircuitBreaker.State state, String detail) {
            context.emit(new ConfigWarningEvent(source,
                    "脚本 " + scriptId + " 已熔断（" + state.displayName() + "）：" + detail
                            + "；工具仍在清单里，熔断期满会自动恢复"));
        }

        @Override
        public void onRecovered(String scriptId, String detail) {
            context.emit(new ConfigWarningEvent(source, "脚本 " + scriptId + " 已恢复：" + detail));
        }
    }
}
