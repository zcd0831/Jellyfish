package zcd.jellyfish.plugin.shell;

import zcd.jellyfish.api.JellyfishException;

import java.util.Collections;
import java.util.Map;

/**
 * {@code shell} 工具参数的读取与校验：把「模型给的 JSON」当成不受信任的输入来读。
 * <p>
 * 参数由内核解析成 {@code Map<String, Object>} 后交给工具，因此这里拿到的值可能是
 * {@code String}、{@code Integer}/{@code Double}（JSON 数字）或 {@code Boolean}。
 * 类型不对必须在<b>启动任何进程之前</b>变成一条可读的错误——工具抛出的异常会被内核转成
 * 工具结果回灌给模型，因此错误文案是模型自我纠正的唯一依据。
 * <p>
 * 无状态、不可变。
 *
 * @author zcd
 */
final class ShellArguments {

    /** 原始参数。 */
    private final Map<String, Object> values;

    /**
     * 构造参数读取器。
     *
     * @param values 原始参数，可为 {@code null}（等价空参数）
     */
    ShellArguments(Map<String, Object> values) {
        this.values = values == null ? Collections.<String, Object>emptyMap() : values;
    }

    /**
     * 读取命令原文。
     *
     * @return 去掉首尾空白的命令原文，保证非空
     * @throws JellyfishException 缺失、类型不符或为空白时抛出
     */
    String requireCommand() {
        Object raw = require("command");
        if (!(raw instanceof String)) {
            throw new JellyfishException("参数 command 必须是字符串，实际是 " + raw);
        }
        String command = ((String) raw).trim();
        if (command.isEmpty()) {
            throw new JellyfishException("参数 command 不能为空");
        }
        return command;
    }

    /**
     * 读取可选的 {@code cwd} 参数。
     *
     * @return 目录文本；未提供时返回 {@code null}
     * @throws JellyfishException 类型不符时抛出
     */
    String optionalCwd() {
        Object raw = values.get("cwd");
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String)) {
            throw new JellyfishException("参数 cwd 必须是字符串，实际是 " + raw);
        }
        String cwd = ((String) raw).trim();
        return cwd.isEmpty() ? null : cwd;
    }

    /**
     * 读取可选的 {@code timeout_seconds} 参数。
     *
     * @return 秒数；未提供时返回 {@code -1}
     * @throws JellyfishException 类型不符或不是正数时抛出
     */
    int optionalTimeoutSeconds() {
        Object raw = values.get("timeout_seconds");
        if (raw == null) {
            return -1;
        }
        Integer seconds = asInt(raw);
        if (seconds == null) {
            throw new JellyfishException("参数 timeout_seconds 必须是整数，实际是 " + raw);
        }
        if (seconds.intValue() <= 0) {
            // 刻意不把 0 解释成「不超时」：内核里没有「无上限」这个语义，
            // 而静默地把 0 当成缺省值会让模型以为自己写的限制生效了
            throw new JellyfishException("参数 timeout_seconds 必须大于 0，实际是 " + seconds);
        }
        return seconds.intValue();
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
     * 把参数值解析成整数。
     * <p>
     * 模型常把数字写成字符串（如 {@code "30"}），能解析就接受，解析不了才报错。
     *
     * @param raw 原始值
     * @return 整数；无法解析时返回 {@code null}
     */
    private static Integer asInt(Object raw) {
        if (raw instanceof Number) {
            Number number = (Number) raw;
            double asDouble = number.doubleValue();
            if (asDouble != Math.floor(asDouble) || Double.isInfinite(asDouble)) {
                return null;
            }
            return Integer.valueOf((int) asDouble);
        }
        if (raw instanceof String) {
            try {
                return Integer.valueOf(Integer.parseInt(((String) raw).trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
