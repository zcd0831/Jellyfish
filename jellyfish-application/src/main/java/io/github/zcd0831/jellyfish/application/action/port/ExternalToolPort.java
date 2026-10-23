package io.github.zcd0831.jellyfish.application.action.port;

import io.github.zcd0831.jellyfish.domain.action.tool.ToolDefinition;
import java.util.Map;

/**
 * REST / MCP / NATIVE 出站调用。规则出处：模块-应用层 §6。
 */
public interface ExternalToolPort {

    String invoke(ToolDefinition tool, Map<String, Object> args);
}
