package io.github.zcd0831.jellyfish.shell.tui;

import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一轮的用量与进度小结（界面用，不落盘）。
 * <p>
 * 事件只带 `message`（§6 约定），所以这里从 `USAGE` 文案里取数字：
 * `+150 / 累计 300 / 上限 1000000`。数字口径由应用层定义，外壳只负责汇总展示。
 */
public final class TurnStats {

    private static final Pattern USAGE = Pattern.compile("\\+(\\d+)\\s*/\\s*累计\\s*(\\d+)\\s*/\\s*上限\\s*(\\d+)");

    private int modelCalls;
    private long tokensThisTurn;
    private long tokensTotal;
    private long limit;
    private int stepsStarted;
    private int stepsTotal;
    private int tools;
    private long startedAt;

    public void reset() {
        modelCalls = 0;
        tokensThisTurn = 0;
        tokensTotal = 0;
        limit = 0;
        stepsStarted = 0;
        stepsTotal = 0;
        tools = 0;
        startedAt = System.currentTimeMillis();
    }

    public void observe(ProgressEvent event) {
        switch (event.kind()) {
            case USAGE:
                observeUsage(event.message());
                break;
            case TOOL_STARTED:
                tools++;
                break;
            case STEP_STARTED:
                stepsStarted++;
                stepsTotal = Math.max(stepsTotal, stepTotalOf(event.message()));
                break;
            default:
                // 其余事件不参与小结
        }
    }

    /** 没有发生任何值得总结的事（例如空输入）就返回 null，避免刷屏。 */
    public String summary() {
        if (modelCalls == 0) {
            return null;
        }
        StringBuilder text = new StringBuilder("—— 本轮 ");
        text.append(modelCalls).append(" 次模型调用");
        if (tokensThisTurn > 0) {
            text.append(" / +").append(tokensThisTurn).append(" tokens");
        }
        if (tokensTotal > 0) {
            text.append(" / 累计 ").append(tokensTotal);
            if (limit > 0) {
                text.append(" / 上限 ").append(limit);
            }
        }
        if (stepsStarted > 0) {
            text.append(" / ").append(stepsStarted).append('/').append(stepsTotal).append(" 步");
        }
        if (tools > 0) {
            text.append(" / ").append(tools).append(" 个工具");
        }
        text.append(" / ").append(elapsedSeconds()).append("s ——");
        return text.toString();
    }

    private void observeUsage(String message) {
        modelCalls++;
        Matcher matcher = USAGE.matcher(message == null ? "" : message);
        if (!matcher.find()) {
            return;
        }
        tokensThisTurn += Long.parseLong(matcher.group(1));
        tokensTotal = Long.parseLong(matcher.group(2));
        limit = Long.parseLong(matcher.group(3));
    }

    /** `第 2/5 步：…` → 5；取不到就沿用已有值。 */
    private static int stepTotalOf(String message) {
        if (message == null) {
            return 0;
        }
        Matcher matcher = Pattern.compile("第\\s*\\d+\\s*/\\s*(\\d+)\\s*步").matcher(message);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private String elapsedSeconds() {
        long millis = System.currentTimeMillis() - startedAt;
        return String.format(java.util.Locale.ROOT, "%.1f", millis / 1000.0);
    }
}
