package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.shared.CancellationToken;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;

/**
 * 编排扩展点：交付一轮运行的唯一入口。默认实现是 {@link ExecutionLoop}，
 * 插件可在此实现完全不同的循环（标记 `experimental`，不承诺跨版本兼容）。
 * 规则出处：模块-规划 §4、D24-5。
 */
public interface SessionRunner {

    RunOutcome run(Session session, ChunkSink sink, CancellationToken cancellation, RunContext context);
}
