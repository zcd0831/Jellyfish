package io.github.zcd0831.jellyfish.shell.tui;

import io.github.zcd0831.jellyfish.domain.shared.SessionId;

/**
 * 外壳的"当前会话"游标：输入循环与命令分派共用同一个。
 * <p>
 * 单独拎出来是为了让命令分派不必反向依赖 {@link TuiApp}（只依赖这个游标即可）。
 * 规则出处：08-application §8.9.4.2（外壳只调用例）。
 */
final class SessionCursor {

    private volatile SessionId current;

    SessionId current() {
        return current;
    }

    void moveTo(SessionId sessionId) {
        this.current = sessionId;
    }

    void clear() {
        this.current = null;
    }
}
