package zcd.jellyfish.plugin.tools;

import zcd.jellyfish.api.JellyfishException;

import java.util.Collections;
import java.util.Map;

/**
 * 工具参数的读取与校验：把「模型给的 JSON」当成不受信任的输入来读。
 * <p>
 * 参数由内核解析成 {@code Map<String, Object>} 后交给工具，因此这里拿到的值可能是
 * {@code String}、{@code Integer}/{@code Double}（JSON 数字）或 {@code Boolean}，
 * 缺失与类型不对都必须在这里变成一条<b>可读的错误</b>——
 * 工具抛出的异常会被内核转成工具结果回灌给模型，因此错误文案是模型自我纠正的唯一依据。
 * <p>
 * 无状态、不可变。
 *
 * @author zcd
 */
final class ToolArguments {

    /** 原始参数。 */
    private final Map<String, Object> values;

    /**
     * 构造参数读取器。
     *
     * @param values 原始参数，可为 {@code null}（等价空参数）
     */
    ToolArguments(Map<String, Object> values) {
        this.values = values == null ? Collections.<String, Object>emptyMap() : values;
    }

    /**
     * 读取必需的非空字符串参数。
     *
     * @param name 参数名
     * @return 去掉首尾空白后的值
     * @throws JellyfishException 缺失、类型不符或为空白时抛出
     */
    String requireString(String name) {
        String value = asText(name, require(name));
        if (value.trim().isEmpty()) {
            throw new JellyfishException("参数 " + name + " 不能为空");
        }
        return value.trim();
    }

    /**
     * 读取必需的文本参数，允许为空串。
     * <p>
     * 与 {@link #requireString(String)} 分开：{@code write_file} 的正文、
     * {@code edit_file} 的新文本都可能合法地是空串，用「非空白」校验会把正常调用判成错误。
     *
     * @param name 参数名
     * @return 原样值，可为空串
     * @throws JellyfishException 缺失或类型不符时抛出
     */
    String requireText(String name) {
        return asText(name, require(name));
    }

    /**
     * 读取可选的字符串参数。
     *
     * @param name     参数名
     * @param fallback 缺失时的回退值
     * @return 值；缺失或为 {@code null} 时返回回退值
     * @throws JellyfishException 类型不符时抛出
     */
    String optionalString(String name, String fallback) {
        Object value = values.get(name);
        if (value == null) {
            return fallback;
        }
        String text = asText(name, value).trim();
        return text.isEmpty() ? fallback : text;
    }

    /**
     * 读取可选的整数参数。
     *
     * @param name     参数名
     * @param fallback 缺失时的回退值
     * @return 值；缺失或为 {@code null} 时返回回退值
     * @throws JellyfishException 不是整数时抛出
     */
    int optionalInt(String name, int fallback) {
        Object value = values.get(name);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number) {
            Number number = (Number) value;
            double asDouble = number.doubleValue();
            if (asDouble != Math.floor(asDouble) || Double.isInfinite(asDouble)) {
                throw new JellyfishException("参数 " + name + " 必须是整数，实际是 " + value);
            }
            return (int) asDouble;
        }
        // 模型常把数字写成字符串（如 "10"），能解析就接受，解析不了才报错
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new JellyfishException("参数 " + name + " 必须是整数，实际是 " + value);
        }
    }

    /**
     * 读取可选的布尔参数。
     *
     * @param name     参数名
     * @param fallback 缺失时的回退值
     * @return 值；缺失或为 {@code null} 时返回回退值
     * @throws JellyfishException 不是布尔时抛出
     */
    boolean optionalBoolean(String name, boolean fallback) {
        Object value = values.get(name);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        throw new JellyfishException("参数 " + name + " 必须是布尔值，实际是 " + value);
    }

    /**
     * 取出必需参数。
     *
     * @param name 参数名
     * @return 原始值
     * @throws JellyfishException 缺失时抛出
     */
    private Object require(String name) {
        Object value = values.get(name);
        if (value == null) {
            throw new JellyfishException("缺少必需参数: " + name);
        }
        return value;
    }

    /**
     * 把值断言成字符串。
     *
     * @param name  参数名
     * @param value 原始值
     * @return 字符串值
     * @throws JellyfishException 不是字符串时抛出
     */
    private static String asText(String name, Object value) {
        if (!(value instanceof String)) {
            throw new JellyfishException("参数 " + name + " 必须是字符串，实际是 " + value);
        }
        return (String) value;
    }
}
