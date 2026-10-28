package io.github.zcd0831.jellyfish.domain.action.tool;

/**
 * 工具来源。`PLUGIN` 只作来源标记（审计、界面、免审批判定）；
 * 执行仍然查同一个处理器注册表。规则出处：05-action §5.6。
 */
public enum SourceType {
    INTERNAL,
    NATIVE,
    REST,
    MCP,
    PLUGIN
}
