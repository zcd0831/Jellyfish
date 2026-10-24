package io.github.zcd0831.jellyfish.shell.tui;

import io.github.zcd0831.jellyfish.application.shared.CancellationToken;

import java.util.ArrayList;
import java.util.List;

/**
 * 外壳持有的唯一取消令牌（§8.4.1）：Ctrl-C 与 `/cancel` 都走 {@link #cancel()}。
 * 每轮开始 {@link #reset()}，避免上一轮的取消影响下一轮。
 */
public final class ShellCancellationToken implements CancellationToken {

    private final List<Runnable> callbacks = new ArrayList<Runnable>();
    private volatile boolean cancelled;

    public void onCancel(Runnable callback) {
        synchronized (callbacks) {
            callbacks.add(callback);
        }
        if (cancelled) {
            callback.run();
        }
    }

    /** 一次取消：先把标志置上，再让所有等待点（HTTP 读、ask）尽快退出。 */
    public void cancel() {
        cancelled = true;
        List<Runnable> snapshot;
        synchronized (callbacks) {
            snapshot = new ArrayList<Runnable>(callbacks);
        }
        for (Runnable callback : snapshot) {
            callback.run();
        }
    }

    public void reset() {
        cancelled = false;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void throwIfCancelled() {
        if (cancelled) {
            throw new java.util.concurrent.CancellationException("用户取消了本轮");
        }
    }
}
