package io.github.zcd0831.jellyfish.shell.tui;

import io.github.zcd0831.jellyfish.application.shared.ChunkSink;

/**
 * 本轮输出：记住有没有打出过字（取消时据此说清"上面那段没保存"）。
 */
final class TurnSink implements ChunkSink {

    private final AnsiRenderer renderer;
    private boolean started;

    TurnSink(AnsiRenderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public void accept(String delta) {
        if (!started) {
            renderer.status("");
            renderer.assistantPrefix();
            started = true;
        }
        renderer.append(delta);
    }

    boolean rendered() {
        return started;
    }
}
