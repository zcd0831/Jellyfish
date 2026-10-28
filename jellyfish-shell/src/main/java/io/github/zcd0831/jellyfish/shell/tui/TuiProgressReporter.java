package io.github.zcd0831.jellyfish.shell.tui;

import io.github.zcd0831.jellyfish.application.shared.ProgressEvent;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;

/**
 * 进度用状态行原地覆盖（\r），不整屏重绘；丢弃不影响业务。
 */
public final class TuiProgressReporter implements ProgressPort {

    private final AnsiRenderer renderer;
    private final TurnStats stats;

    public TuiProgressReporter(AnsiRenderer renderer, TurnStats stats) {
        this.renderer = renderer;
        this.stats = stats;
    }

    @Override
    public void emit(ProgressEvent event) {
        stats.observe(event);
        switch (event.kind()) {
            // 常驻行：计划、步骤、工具调用是"发生了什么"，不能被下一行冲掉
            case PLAN_CREATED:
            case STEP_STARTED:
                renderer.status("");
                renderer.info("· " + event.message());
                break;
            case STEP_FINISHED:
                renderer.status("");
                renderer.info("✓ " + event.message());
                break;
            case TOOL_STARTED:
                renderer.tool(event.message(), "");
                break;
            case TOOL_FINISHED:
                renderer.toolDone(event.message());
                break;
            // 上下文占用只喂底部状态栏：再打一行就是重复
            case CONTEXT_USAGE:
                break;
            // 状态行：可丢、会变的（压缩中、重试倒计时、用量）
            default:
                renderer.status("· " + event.message());
        }
    }
}
