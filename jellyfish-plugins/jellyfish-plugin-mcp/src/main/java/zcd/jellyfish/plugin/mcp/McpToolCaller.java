package zcd.jellyfish.plugin.mcp;

import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolMetadata;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个 MCP 工具在内核里的处理器：把工具调用转给对面，再把结果映射成内核的工具结果。
 * <p>
 * <b>「协议成功但工具失败」要如实带上失败标记</b>：{@code isError} 是 server 明确答复的一种正常结果，
 * 而它必须能在界面上看出来（走 {@link ToolMetadata#KEY_TERMINAL}），否则用户看到的就是
 * 「工具跑完了，输出里说什么东西不对」。这与「非零退出码如实报告」是同一条口径。
 * <p>
 * <b>首行写结论</b>：正文由 server 决定，可能是一整篇；失败时在最前面加一行说明，
 * 让模型在读到正文之前就知道这次调用成没成。
 * <p>
 * 无状态（除了捕获的连接引用），可安全复用。
 *
 * @author zcd
 */
final class McpToolCaller implements ExtensionHandler<ToolCallRequest, ToolCallResult> {

    /** 工具定义。 */
    private final McpToolDefinition definition;

    /** 调用发起方。 */
    private final McpInvoker invoker;

    /** 单次调用超时毫秒数。 */
    private final long timeoutMillis;

    /**
     * 构造处理器。
     *
     * @param definition    工具定义，不可为 {@code null}
     * @param invoker       调用发起方，不可为 {@code null}
     * @param timeoutMillis 单次调用超时毫秒数；{@code <= 0} 表示不超时
     */
    McpToolCaller(McpToolDefinition definition, McpInvoker invoker, long timeoutMillis) {
        this.definition = definition;
        this.invoker = invoker;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public ToolCallResult handle(ToolCallRequest request) {
        McpInvoker.McpCallOutcome outcome = invoker.callTool(definition.originalName(),
                request.getArguments(), timeoutMillis, request.getCancellationToken());
        return new ToolCallResult(definition.qualifiedName(), render(outcome), metadata(outcome));
    }

    /**
     * 组装回灌文本：失败时在最前面加一行结论。
     *
     * @param outcome 调用结果
     * @return 回灌文本
     */
    private static String render(McpInvoker.McpCallOutcome outcome) {
        if (!outcome.error()) {
            return outcome.text();
        }
        return "[MCP 工具报告失败]\n" + outcome.text();
    }

    /**
     * 组装结构化元数据。
     * <p>
     * 三个约定键里用两个：{@code summary} 说明「刚才那一行是什么事」，{@code terminal} 在 server
     * 报告失败时取一个不等于 {@code COMPLETED} 的取值，让界面渲染警示标记。另外三个自定键
     * （server / tool / 往返耗时）由界面按需读取——内核只透传。
     *
     * @param outcome 调用结果
     * @return 元数据，保证非 {@code null}
     */
    private Map<String, Object> metadata(McpInvoker.McpCallOutcome outcome) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put(ToolMetadata.KEY_SUMMARY, "mcp " + definition.serverId() + " · "
                + definition.originalName() + " · " + outcome.durationMillis() + " ms");
        metadata.put("mcpServer", definition.serverId());
        metadata.put("mcpTool", definition.originalName());
        metadata.put("durationMs", Long.valueOf(outcome.durationMillis()));
        if (outcome.error()) {
            metadata.put(ToolMetadata.KEY_TERMINAL, "FAILED");
        }
        return metadata;
    }
}
