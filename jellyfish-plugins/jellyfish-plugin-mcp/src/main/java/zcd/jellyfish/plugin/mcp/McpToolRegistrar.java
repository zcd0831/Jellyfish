package zcd.jellyfish.plugin.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.plugin.PluginContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册器：把「某个 server 当前提供哪些工具」落到内核的扩展点上。
 * <p>
 * <b>每个 server 一个子上下文</b>：注册走 {@code PluginContext.subContext(serverId)}，落在
 * {@code jellyfish-mcp::<serverId>} 下。诊断输出因此能指出「这个工具是哪个 server 提供的」，
 * 而框架卸载时按命名空间一次性把整份注册收干净。
 * <p>
 * <b>整体替换而不是增量增删</b>：{@code tools/list} 返回的是「当前全部」，而工具的名片
 * （描述、参数 Schema、只读标记）都可能变。增量增删要判断「这个工具是新的还是改过的」，
 * 而那条判断一旦漏了「改过」这一档，模型就会拿着旧 Schema 去调新工具。
 * 整体替换的代价是注册表在极短窗口内少了几个名字——而那个窗口里最坏的结果是模型看不到这个工具。
 * <p>
 * <b>单个工具注册失败不拖垮其余</b>：名字与内置工具撞了（关掉前缀时的 {@code read_file}）
 * 只记一条告警并跳过它。这与脚本桥接插件「单个脚本问题只记台账」是同一条取舍。
 * <p>
 * 线程安全。
 *
 * @author zcd
 */
final class McpToolRegistrar {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(McpToolRegistrar.class);

    /** 插件上下文：注册与子上下文的来源。 */
    private final PluginContext context;

    /** 共享状态。 */
    private final McpRegistry registry;

    /** server 标识 → 已注册的句柄。 */
    private final Map<String, List<Subscription>> registered =
            new ConcurrentHashMap<String, List<Subscription>>();

    /**
     * 构造注册器。
     *
     * @param context  插件上下文，不可为 {@code null}
     * @param registry 共享状态，不可为 {@code null}
     */
    McpToolRegistrar(PluginContext context, McpRegistry registry) {
        this.context = context;
        this.registry = registry;
    }

    /**
     * 用新的工具清单替换某个 server 的注册。
     *
     * @param invoker       工具调用发起方，不可为 {@code null}
     * @param server        服务配置，不可为 {@code null}
     * @param tools         新的工具清单，不可为 {@code null}
     */
    void apply(McpInvoker invoker, McpServerConfig server, List<McpToolDefinition> tools) {
        close(server.id());
        List<Subscription> created = new ArrayList<Subscription>(tools.size());
        PluginContext serverContext = context.subContext(server.id());
        long timeoutMillis = server.callTimeoutSeconds() * 1000L;
        for (McpToolDefinition tool : tools) {
            try {
                created.add(serverContext.handle(ToolCallRequest.class, tool.qualifiedName(),
                        tool.descriptor(), new McpToolCaller(tool, invoker, timeoutMillis)));
            } catch (RuntimeException e) {
                // 名字被占了（关掉前缀时最容易撞上内置工具）：跳过它，让其余工具照常可用
                LOG.warn("MCP 工具注册失败，已跳过: server={} tool={} reason={}", server.id(),
                        tool.qualifiedName(), e.getMessage());
                registry.noteWarning(server.id(),
                        "工具 " + tool.qualifiedName() + " 注册失败：" + e.getMessage());
            }
        }
        registered.put(server.id(), created);
        registry.replaceTools(server.id(), tools);
    }

    /**
     * 注销某个 server 的全部工具。
     *
     * @param serverId 服务标识
     */
    void close(String serverId) {
        List<Subscription> previous = registered.remove(serverId);
        if (previous == null) {
            return;
        }
        for (Subscription subscription : previous) {
            subscription.close();
        }
        registry.removeServer(serverId);
    }

    /**
     * 注销全部工具。
     */
    void closeAll() {
        for (String serverId : new ArrayList<String>(registered.keySet())) {
            close(serverId);
        }
    }
}
