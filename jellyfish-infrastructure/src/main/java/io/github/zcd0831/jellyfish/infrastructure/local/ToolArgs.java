package io.github.zcd0831.jellyfish.infrastructure.local;

import java.util.Map;

/**
 * 工具入参的取值口径：缺参数、空串、类型不对都是 `IllegalArgumentException`
 * （由 {@link LocalToolHandlers} 的 guarded 统一转成"一种工具结果"）。
 * 规则出处：05-action §5.8.1 规则 9、§7.1 规则 9。
 */
final class ToolArgs {

    static String string(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            throw new IllegalArgumentException("缺少参数：" + key);
        }
        String text = String.valueOf(value);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("参数为空：" + key);
        }
        return text;
    }

    static String optional(Map<String, Object> args, String key, String fallback) {
        Object value = args.get(key);
        return value == null || String.valueOf(value).isEmpty() ? fallback : String.valueOf(value);
    }

    static int number(Map<String, Object> args, String key, int fallback) {
        Object value = args.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数不是整数：" + key);
        }
    }

    static boolean flag(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(String.valueOf(value));
    }

}
