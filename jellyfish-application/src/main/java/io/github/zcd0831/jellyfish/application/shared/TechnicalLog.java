package io.github.zcd0831.jellyfish.application.shared;

import java.util.ArrayList;
import java.util.List;

/**
 * 技术事件出口：应用层只声明"发生了什么"，落盘由基础设施实现（D14）。
 * 测试可注入收集器断言"该打的点打了"。
 */
public final class TechnicalLog {

    /** 一条技术事件：事件名 + 若干 key=value 字段。 */
    public interface Sink {
        void emit(String event, long durationMillis, String... fields);
    }

    private static volatile Sink sink = new Sink() {
        @Override
        public void emit(String event, long durationMillis, String... fields) {
            // 未装配时丢弃：日志不该成为业务依赖
        }
    };

    private TechnicalLog() {
    }

    public static void use(Sink replacement) {
        sink = replacement;
    }

    public static void event(String event, long durationMillis, String... fields) {
        sink.emit(event, durationMillis, fields);
    }

    public static void fold(String sessionId, int usedTokens) {
        event("context.fold", 0L, "s=" + sessionId, "tokIn=" + usedTokens);
    }

    public static void verified(String planId, String stepId, String outcome, long durationMillis) {
        event("verification.done", durationMillis, "plan=" + planId, "step=" + stepId, "code=" + outcome);
    }

    public static void collected() {
        event("technical-log.collect", 0L);
    }

    static List<String> describe(String event, String... fields) {
        List<String> out = new ArrayList<String>();
        out.add(event);
        for (String field : fields) {
            out.add(field);
        }
        return out;
    }
}
