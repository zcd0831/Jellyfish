package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.perception.ContextWindowService;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;

import io.github.zcd0831.jellyfish.domain.perception.session.Session;

/**
 * 外循环。S1 没有计划聚合，所以只走"无计划"分支：收敛即结束；撞轮次上限也只结束并如实回报。
 * 计划相关的三条出口在 S2 接入。规则出处：模型图 §5.1、模块-规划 §8.1。
 */
public final class ExecutionLoop {

    public enum Outcome {
        CONVERGED,
        ROUNDS_EXHAUSTED
    }

    private final ContextWindowService contextWindow;
    private final ReActLoop reActLoop;

    public ExecutionLoop(ContextWindowService contextWindow, ReActLoop reActLoop) {
        this.contextWindow = contextWindow;
        this.reActLoop = reActLoop;
    }

    public Outcome run(Session session, ChunkSink sink) {
        contextWindow.ensureWithinLimit(session);
        IterationResult result = reActLoop.runIteration(session, null, sink);
        return result.isConverged() ? Outcome.CONVERGED : Outcome.ROUNDS_EXHAUSTED;
    }
}
