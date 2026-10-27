package io.github.zcd0831.jellyfish.shell.tui;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * `/` 命令的唯一清单：补全与 `/help` 都从这里取，避免两处各写一份后走偏。
 */
final class Commands {

    /** 命令名（含前导 `/`），顺序即 `/help` 的展示顺序。 */
    static final List<String> NAMES = Collections.unmodifiableList(Arrays.asList(
            "/sessions", "/open", "/new", "/cancel", "/archive", "/delete",
            "/model", "/policy", "/plugins",
            "/tools", "/agents", "/models", "/providers", "/skills",
            "/help", "/exit"));

    /** 不带参数的会话命令：没有会话时不允许执行（`/sessions` 等目录命令例外）。 */
    static final List<String> NEEDS_SESSION = Collections.unmodifiableList(Arrays.asList(
            "/cancel", "/archive", "/delete", "/model"));

    private Commands() {
    }

    static String helpLine() {
        return String.join(" ", NAMES);
    }

    static boolean needsSession(String name) {
        return NEEDS_SESSION.contains(name);
    }
}
