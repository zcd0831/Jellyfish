package io.github.zcd0831.jellyfish.shell.tui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * `/` 命令的唯一清单：补全、`/help`、参数提示都从这里取，避免几处各写一份后走偏。
 * <p>规则出处：08-application §8.9.4.2（外壳只调用例）、09-infrastructure §9.10（命令面清单化）。
 */
final class Commands {

    /** 命令名 → 参数提示（`/help` 直接拼这段，不再另写一份文案）。顺序即 `/help` 的展示顺序。 */
    static final Map<String, String> USAGE;

    static {
        Map<String, String> usage = new LinkedHashMap<String, String>();
        usage.put("/sessions", null);
        usage.put("/open", "<id>");
        usage.put("/new", null);
        usage.put("/archive", null);
        usage.put("/delete", null);
        usage.put("/cancel", "[id]");
        usage.put("/policy", "k=v");
        usage.put("/model", "<id>");
        usage.put("/tools", null);
        usage.put("/agents", null);
        usage.put("/models", null);
        usage.put("/providers", null);
        usage.put("/skills", null);
        usage.put("/plugins", null);
        usage.put("/help", null);
        usage.put("/exit", null);
        USAGE = Collections.unmodifiableMap(usage);
    }

    /** 命令名（含前导 `/`）。 */
    static final List<String> NAMES =
            Collections.unmodifiableList(new ArrayList<String>(USAGE.keySet()));

    /** 不带参数的会话命令：没有会话时不允许执行（`/sessions` 等目录命令例外）。 */
    static final List<String> NEEDS_SESSION = Collections.unmodifiableList(Arrays.asList(
            "/archive", "/delete", "/model"));

    private Commands() {
    }

    /** `/help` 的正文：命令与参数提示同源，改清单即改帮助。 */
    static String helpLine() {
        StringBuilder line = new StringBuilder();
        for (Map.Entry<String, String> entry : USAGE.entrySet()) {
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(entry.getKey());
            if (entry.getValue() != null) {
                line.append(' ').append(entry.getValue());
            }
        }
        return line.toString();
    }

    static boolean needsSession(String name) {
        return NEEDS_SESSION.contains(name);
    }
}
