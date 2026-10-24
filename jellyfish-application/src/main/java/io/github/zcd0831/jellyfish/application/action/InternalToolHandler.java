package io.github.zcd0831.jellyfish.application.action;

import io.github.zcd0831.jellyfish.domain.perception.session.Session;

import java.util.Map;

/**
 * 内置工具的处理器：本来就在应用层（模块-应用层 §6 的端口收缩说明），不需要端口。
 * 规则出处：模块-行动 §3.1 处理器归属一列。
 */
public interface InternalToolHandler {

    ToolExecOutcome handle(Session session, Map<String, Object> args);
}
