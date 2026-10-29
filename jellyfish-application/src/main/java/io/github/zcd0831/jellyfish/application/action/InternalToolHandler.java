package io.github.zcd0831.jellyfish.application.action;

import io.github.zcd0831.jellyfish.application.shared.CancellationToken;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;

import java.util.Map;

/**
 * 内置工具的处理器：本来就在应用层（08-application §8.7 的端口收缩说明），不需要端口。
 * 规则出处：05-action §5.4.1 处理器归属一列。
 */
public interface InternalToolHandler {

    ToolExecOutcome handle(Session session, Map<String, Object> args);

    /**
     * 带终止信号的版本。默认忽略信号（内部编排处理器都不长跑）；
     * 会阻塞的处理器（本地能力里的 <code>run_command</code>）必须覆写它。
     * 规则出处：08-application §8.9.4.1（终止要能到工具执行）。
     */
    default ToolExecOutcome handle(Session session, Map<String, Object> args, CancellationToken token) {
        return handle(session, args);
    }
}
