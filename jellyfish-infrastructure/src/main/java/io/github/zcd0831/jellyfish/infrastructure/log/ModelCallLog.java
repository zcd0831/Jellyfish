package io.github.zcd0831.jellyfish.infrastructure.log;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;

/**
 * 模型调用的技术事件落点：重试 / 降级 / 摘要 / 验证耗时都走这里。
 * 只记技术事件，不记对话正文。
 */
public final class ModelCallLog {

    private static final Logger LOG = LogManager.getLogger("jellyfish.model");

    private ModelCallLog() {
    }

    public static void retry(String model, int retry, long delayMs) {
        ThreadContext.put("model", model);
        ThreadContext.put("retry", String.valueOf(retry));
        ThreadContext.put("durMs", String.valueOf(delayMs));
        ThreadContext.put("ev", "model.retry");
        try {
            LOG.info("安排重试");
        } finally {
            clear();
        }
    }

    public static void switched(String from, String to) {
        ThreadContext.put("model", to);
        ThreadContext.put("ev", "model.switched");
        try {
            LOG.info("降级到 " + to + "（原 " + from + "）");
        } finally {
            clear();
        }
    }

    public static void unavailable(String model, String code) {
        ThreadContext.put("model", model);
        ThreadContext.put("code", code);
        ThreadContext.put("ev", "model.unavailable");
        try {
            LOG.warn("模型不可用");
        } finally {
            clear();
        }
    }

    private static void clear() {
        ThreadContext.remove("model");
        ThreadContext.remove("retry");
        ThreadContext.remove("durMs");
        ThreadContext.remove("ev");
        ThreadContext.remove("code");
    }
}
