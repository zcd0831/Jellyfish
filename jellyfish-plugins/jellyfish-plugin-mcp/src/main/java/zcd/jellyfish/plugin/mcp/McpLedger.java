package zcd.jellyfish.plugin.mcp;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /mcp} 命令的台账渲染：把每个 server 的连接状态、工具数与最近的问题落成一段可读文本。
 * <p>
 * <b>为什么这条命令必须有</b>：MCP 的现场表现几乎全是「工具没出现」或「工具一会儿有一会儿没有」，
 * 而真正的原因（进程起不来、握手指令不对、清单被上限截断、名字撞了内置工具）全在运行期，
 * 没有任何界面。把台账放在一条命令上，排查就只需要敲一次，不必去翻日志。
 * <p>
 * <b>连不上是常态，不是异常</b>：server 要装、要联网、要凭证，用户的机器上它经常起不来。
 * 台账因此把失败原因与告警都放在台面上，而不是把失败藏成一句「0 个工具」。
 * <p>
 * 不能实例化。
 *
 * @author zcd
 */
final class McpLedger {

    /** 工具清单最多列出的行数：再多就退化成一份目录，反而挤掉真正重要的状态。 */
    private static final int MAX_TOOL_LINES = 60;

    /**
     * 工具类，禁止实例化。
     */
    private McpLedger() {
    }

    /**
     * 渲染台账。
     *
     * @param config   配置，不可为 {@code null}
     * @param registry 共享状态，不可为 {@code null}
     * @return 台账文本，保证非 {@code null}
     */
    static String render(McpConfig config, McpRegistry registry) {
        StringBuilder text = new StringBuilder("mcp 插件\n");
        text.append("配置: servers=").append(config.servers().size())
                .append(" 前缀=").append(config.toolPrefix())
                .append(" 工具名上限=").append(config.toolNameMaxLength())
                .append(" 写类工具需审批=").append(config.askWriteTools())
                .append(" 单server工具上限=").append(config.maxToolsPerServer()).append('\n');
        List<McpRegistry.ServerStatus> statuses = registry.snapshot();
        if (statuses.isEmpty()) {
            text.append("没有配置任何 MCP server：在 plugins.configurations.jellyfish-mcp.servers 里添加。\n");
            return text.toString();
        }
        text.append("servers:\n");
        for (McpRegistry.ServerStatus status : statuses) {
            text.append("  - ").append(status.serverId())
                    .append("（").append(status.state().displayName()).append("）")
                    .append("工具 ").append(status.toolCount()).append(" 个");
            if (!status.detail().isEmpty()) {
                text.append(" · ").append(status.detail());
            }
            text.append('\n');
            appendIfPresent(text, "最近错误", status.lastError());
            appendIfPresent(text, "最近告警", status.lastWarning());
            if (status.lateResponses() > 0) {
                text.append("    迟到应答（已丢弃）: ").append(status.lateResponses()).append('\n');
            }
        }
        appendTools(text, registry, statuses);
        return text.toString();
    }

    /**
     * 追加已注册工具清单。
     *
     * @param text     目标缓冲
     * @param registry 共享状态
     * @param statuses 状态快照，决定输出顺序
     */
    private static void appendTools(StringBuilder text, McpRegistry registry,
                                    List<McpRegistry.ServerStatus> statuses) {
        List<String> tools = new ArrayList<String>();
        for (McpRegistry.ServerStatus status : statuses) {
            tools.addAll(registry.toolsOf(status.serverId()));
        }
        if (tools.isEmpty()) {
            text.append("已注册工具: 0 个\n");
            return;
        }
        text.append("已注册工具: ").append(tools.size()).append(" 个\n");
        int listed = 0;
        for (String tool : tools) {
            if (listed >= MAX_TOOL_LINES) {
                text.append("  …（另有 ").append(tools.size() - listed).append(" 个未列出）\n");
                break;
            }
            text.append("  - ").append(tool)
                    .append(registry.isReadOnly(tool) ? "（只读）" : "").append('\n');
            listed++;
        }
    }

    /**
     * 非空时追加一行。
     *
     * @param text  目标缓冲
     * @param label 标签
     * @param value 值，可为 {@code null}
     */
    private static void appendIfPresent(StringBuilder text, String label, String value) {
        if (value != null && !value.trim().isEmpty()) {
            text.append("    ").append(label).append(": ").append(value.trim()).append('\n');
        }
    }
}
