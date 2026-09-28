package zcd.jellyfish.plugin.shell;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.plugin.JellyfishPlugin;
import zcd.jellyfish.api.plugin.PluginContext;

/**
 * 官方命令行插件：注册 {@code shell} 工具与它的权限分类器。
 * <p>
 * <b>为什么命令行是一个插件而不是内核能力</b>：它与内核生命周期无关，只做两件事——
 * 用能力上下文注册工具、按需执行命令；放在插件里还顺带获得两样东西：
 * {@code jellyfish.json} 里 {@code plugins.configurations.jellyfish-shell} 的可配置性，
 * 以及热部署。内核也不必为「执行命令」这件事背一个总是存在的安全面。
 * <p>
 * <b>它注册两个扩展点，且第二个性质不同</b>：
 * <ul>
 *     <li>{@link ToolCallRequest}（路由键 {@code shell}）：执行命令；</li>
 *     <li>{@link PermissionCheckRequest}（<b>类型级</b>贡献）：给命令分类。
 *     类型级意味着它会看到<b>所有</b>工具调用，因此处理器必须自己先判断工具名——
 *     否则它会给其它工具下错结论。</li>
 * </ul>
 * <b>为什么不注册提示词贡献</b>：工具名片里的描述已经把「cd 不跨调用保留」「优先用
 * read_file」这些话说清楚了，再往 system prompt 里塞一段就是每轮都重复付费。
 * <p>
 * <b>停止时必须终止在途命令</b>：否则用户看到的是「jellyfish 都退出了，那条命令还在跑」，
 * 而它可能正是插件热部署或关闭的原因。
 *
 * @author zcd
 */
public final class ShellPlugin implements JellyfishPlugin {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ShellPlugin.class);

    /** 执行器，{@link #stop()} 需要用它终止在途进程。 */
    private ShellProcessRunner runner;

    @Override
    public void start(PluginContext context) {
        PluginConfig config = PluginConfig.from(context);
        ShellProcessRunner created = new ShellProcessRunner(new CommonsExecShellProcessLauncher());
        ShellTool tool = new ShellTool(config, created);
        // 先装配再注册：处理器一旦注册就可能被调用，依赖必须已经就绪
        context.handle(ToolCallRequest.class, ShellTool.TOOL_NAME, ShellTool.descriptor(), tool);
        context.contribute(PermissionCheckRequest.class, new ShellPermissionContribution(config.commandPolicy()));
        this.runner = created;
        LOG.info("命令行插件已启动: timeout={}s maxTimeout={}s idleTimeout={}s allowed={}",
                Integer.valueOf(config.timeoutSeconds()), Integer.valueOf(config.maxTimeoutSeconds()),
                Integer.valueOf(config.idleTimeoutSeconds()), Integer.valueOf(config.allowedCommands().size()));
    }

    @Override
    public void stop() {
        ShellProcessRunner current = runner;
        runner = null;
        if (current != null) {
            current.killAll();
        }
    }
}
