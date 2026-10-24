package io.github.zcd0831.jellyfish.infrastructure.log;

import io.github.zcd0831.jellyfish.application.shared.TechnicalLog;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;

/**
 * 把应用层声明的技术事件落到 Log4j 的 key=value 行（脱敏由 Layout 统一做）。
 * 规则出处：技术决策 D14、D18。
 */
public final class Log4jTechnicalLog implements TechnicalLog.Sink {

    private static final Logger LOG = LogManager.getLogger("jellyfish.tech");

    @Override
    public void emit(String event, long durationMillis, String... fields) {
        ThreadContext.put("ev", event);
        ThreadContext.put("durMs", String.valueOf(durationMillis));
        for (String field : fields) {
            int eq = field.indexOf('=');
            if (eq > 0) {
                ThreadContext.put(field.substring(0, eq), field.substring(eq + 1));
            }
        }
        try {
            LOG.info(event);
        } finally {
            ThreadContext.clearMap();
        }
    }
}
