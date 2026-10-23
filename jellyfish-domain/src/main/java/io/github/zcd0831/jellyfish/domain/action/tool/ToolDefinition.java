package io.github.zcd0831.jellyfish.domain.action.tool;

import io.github.zcd0831.jellyfish.domain.shared.ToolId;
/** 工具聚合根。规则出处：模块-行动 §7.1。 */
public class ToolDefinition {

    private ToolId id;
    private String name;
    private String description;
    private ToolSchema inputSchema;
    private ToolSchema outputSchema;
    private ToolSource source;
    private SideEffectLevel sideEffect;
    private int timeoutMillis;
    private ToolStatus status;
}
