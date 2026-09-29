package zcd.jellyfish.plugin.mcp;

import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.PermissionVerdict;

/**
 * 把「MCP 工具默认要人看一眼」翻译成权限裁定。
 * <p>
 * <b>为什么需要它</b>：MCP 工具的参数 Schema 由对面决定，而它到底会不会改东西，用户在看到工具
 * 之前无法回答。默认全部可信，等于让「装了一个 server」与「给了它这台机器上的写权限」变成同一件事。
 * <p>
 * <b>为什么是 {@code ASK} 而不是 {@code DENY}</b>：权限裁定里没有「放行」这一态，因此插件只能收紧；
 * 而这里要的恰恰是「让人看一眼再决定」——只读工具免打扰、其余弹一次批准框，与 shell 插件的分类器
 * 是同一种便利机制（区分只读与写入，而不是替用户下结论）。
 * <p>
 * <b>它不是安全边界</b>：{@code readOnlyHint} 由 server 自己填，填错了这里就会放行。真正的边界是
 * 「装了什么 server」以及 {@code askTools} / {@code allowedTools} 那套核心策略——本处理器只是
 * 让默认姿势偏保守一点。
 * <p>
 * <b>为什么在处理器里再判一次工具名</b>：{@link PermissionCheckRequest} 是<b>类型级</b>扩展点，
 * 注册之后每一次工具调用的权限检查都会进来。给别的工具下结论等于越过自己的边界去管别人。
 * <p>
 * 无状态，可安全复用。
 *
 * @author zcd
 */
final class McpPermissionContribution
        implements ExtensionHandler<PermissionCheckRequest, PermissionVerdict> {

    /** 共享状态：判断「这个名字是不是我们的工具」以及「它是不是只读」。 */
    private final McpRegistry registry;

    /** 全局配置：是否开启「写类工具要审批」。 */
    private final McpConfig config;

    /**
     * 构造处理器。
     *
     * @param registry 共享状态，不可为 {@code null}
     * @param config   全局配置，不可为 {@code null}
     */
    McpPermissionContribution(McpRegistry registry, McpConfig config) {
        this.registry = registry;
        this.config = config;
    }

    @Override
    public PermissionVerdict handle(PermissionCheckRequest request) {
        String toolName = request.getToolName();
        if (!registry.isMcpTool(toolName)) {
            return PermissionVerdict.abstain();
        }
        if (!config.askWriteTools() || registry.isReadOnly(toolName)) {
            return PermissionVerdict.abstain();
        }
        return PermissionVerdict.ask("MCP 工具 " + toolName + " 未声明为只读，可能需要确认");
    }
}
