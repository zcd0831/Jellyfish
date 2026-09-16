package zcd.jellyfish.plugin.python;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.plugin.JellyfishPlugin;
import zcd.jellyfish.api.plugin.PluginContext;

/**
 * Python 桥接插件：内核眼里的一个标准 PF4J 插件，背后是 Python 脚本插件。
 * <p>
 * <b>它为什么必须存在</b>：内核的扩展边界由三条硬性质定义，全部发生在 JVM 内——插件身份（注册表的
 * {@code owner}）来自 PF4J 描述符、注册窗口只允许在 {@code start(PluginContext)} 内、
 * 能力只能从 {@code PluginContext} 的四个方法拿到。脚本进程是 JVM 外的东西，既没有 PF4J 身份，
 * 也拿不到 {@code PluginContext}（那是 JVM 对象，跨不过进程边界）。因此必须有一个 JVM 内的代理人，
 * 在 {@code start()} 里替脚本调用 {@code handle} / {@code contribute} / {@code observe} / {@code emit}。
 * <p>
 * <b>注册与进程生命周期解耦</b>：脚本的注册来自脚本目录下的静态清单 {@code manifest.json}，
 * 因此在 {@code start()} 里就能完成全部注册，<b>不需要拉起任何 Python 进程</b>。
 * 好处有三：Python 环境缺失不影响内核启动且工具清单依然完整；没有请求时脚本侧进程数为零；
 * worker 崩溃后可直接重建，不必重新注册。
 * <p>
 * <b>能力上下文每次 start 现造</b>（PF4J 会长期缓存插件实例，{@code stop} 不丢弃它），
 * 因此本插件持有的语言适配与（将来的）网关同样在 {@code start()} 现造、{@code stop()} 释放，
 * 保证「重启插件」真的能读到新配置。
 * <p>
 * 当前阶段（P0）只完成模块骨架与加载链路；清单扫描与注册在 P2 落地，网关与 worker 在 P4 落地。
 *
 * @author zcd
 */
public final class PythonBridgePlugin implements JellyfishPlugin {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(PythonBridgePlugin.class);

    /** 语言适配，在 {@code start()} 现造、{@code stop()} 释放。 */
    private PythonLanguage language;

    /**
     * 启动插件：解析配置、建立语言适配，并（P2 起）按清单注册脚本贡献的转发闭包。
     * <p>
     * <b>不做的事</b>：不拉起 Python 进程、不 import 用户脚本、不读脚本内容。这三件事都会把
     * 「Python 环境是否健全」变成内核能否启动的前提，而本方案刻意让它们解耦。
     *
     * @param context 能力上下文，由框架创建，不可为 {@code null}
     */
    @Override
    public void start(PluginContext context) {
        PythonConfig config = PythonConfig.from(context.configuration());
        language = new PythonLanguage(config.pythonPath());
        LOG.info("Python 桥接插件已启动: pluginId={} scriptsRoot={} language={}",
                context.pluginId(), config.scriptsRoot(), language);
    }

    @Override
    public void stop() {
        if (language == null) {
            return;
        }
        LOG.info("Python 桥接插件已停止: language={}", language);
        language = null;
    }

    /**
     * 获取当前语言适配，供测试断言「start 之后确实建立了适配、stop 之后确实释放了」。
     *
     * @return 语言适配；未启动时为 {@code null}
     */
    PythonLanguage language() {
        return language;
    }
}
