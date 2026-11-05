package io.github.zcd0831.jellyfish.infrastructure.local;

import java.util.Map;

/**
 * fetch_url：只走 http/https，默认回 8000 字符，失败当普通结果（05-action §5.8.1 规则 9）。
 */
final class UrlToolHandler {

    /** 默认最多回这么多字符（规则 9）。 */
    static final int DEFAULT_FETCH_CHARS = 8000;

    private UrlToolHandler() {
    }

    static LocalTool fetchUrl(final UrlFetcher fetcher) {
        return new LocalTool() {
            @Override
            public String run(Map<String, Object> args) {
                String text = fetcher.fetch(ToolArgs.string(args, "url"));
                int max = Math.max(200, ToolArgs.number(args, "maxChars", DEFAULT_FETCH_CHARS));
                return text.length() <= max ? text : text.substring(0, max) + "\n（已截断，共 " + text.length() + " 字符）";
            }
        };
    }
}
