package zcd.jellyfish.plugin.mcp;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.plugin.PluginOwnerNamespace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一个 MCP server 的配置。
 * <p>
 * <b>{@code id} 的规则直接复用 {@link PluginOwnerNamespace#requireChildId}</b>：它会成为
 * {@code PluginContext.subContext(id)} 的子标识，也是工具名前缀的一段。在这里校验而不是等到
 * 启动时才由 {@code subContext} 抛错，是因为配置错误应当在启动期以「哪一项配错了」的形式出现，
 * 而不是在某个目录扫描之后以一个来源拼接异常的形式出现。
 * <p>
 * <b>{@code command} 是命令名或路径，{@code args} 单独给</b>：把整条命令行写成一个字符串就得自己
 * 处理引号与空格，而「带空格的参数」是最常见的用法。这与 shell 插件「命令原文交给 sh -c」是相反的
 * 取舍——那里要的是用户怎么敲就怎么跑，这里要的是「哪个可执行文件、哪些参数」这件事不能被再解释一次。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class McpServerConfig {

    /** 单次工具调用超时的缺省秒数。 */
    static final int DEFAULT_CALL_TIMEOUT_SECONDS = 60;

    /** 连接握手超时的缺省秒数。 */
    static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 15;

    /** 超时秒数允许的最大值。 */
    static final int MAX_TIMEOUT_SECONDS = 3600;

    /** 服务标识，同时是 owner 子命名空间与工具名前缀的一段。 */
    private final String id;

    /** 可执行文件（命令名或绝对路径）。 */
    private final String command;

    /** 参数列表。 */
    private final List<String> args;

    /** 追加到子进程环境里的变量。 */
    private final Map<String, String> env;

    /** 是否启用。 */
    private final boolean enabled;

    /** 连接与握手超时秒数。 */
    private final int connectTimeoutSeconds;

    /** 单次工具调用超时秒数。 */
    private final int callTimeoutSeconds;

    /** 用户显式声明为只读的工具原名。 */
    private final Set<String> readOnlyTools;

    /**
     * 构造配置。
     *
     * @param id                   服务标识
     * @param command              可执行文件
     * @param args                 参数列表
     * @param env                  追加的环境变量
     * @param enabled              是否启用
     * @param connectTimeoutSeconds 连接超时秒数
     * @param callTimeoutSeconds   调用超时秒数
     * @param readOnlyTools         用户声明的只读工具原名
     */
    private McpServerConfig(String id, String command, List<String> args, Map<String, String> env,
                            boolean enabled, int connectTimeoutSeconds, int callTimeoutSeconds,
                            Set<String> readOnlyTools) {
        this.id = id;
        this.command = command;
        this.args = Collections.unmodifiableList(new ArrayList<String>(args));
        this.env = Collections.unmodifiableMap(new LinkedHashMap<String, String>(env));
        this.enabled = enabled;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.callTimeoutSeconds = callTimeoutSeconds;
        this.readOnlyTools = Collections.unmodifiableSet(new HashSet<String>(readOnlyTools));
    }

    /**
     * 从配置项解析。
     *
     * @param raw 单个 server 的配置映射，不可为 {@code null}
     * @return 配置值对象，保证非 {@code null}
     * @throws JellyfishException 缺少必填项或取值非法时抛出
     */
    static McpServerConfig from(Map<String, Object> raw) {
        if (raw == null) {
            throw new JellyfishException("servers 的每一项都必须是对象");
        }
        String id = PluginOwnerNamespace.requireChildId(requireText(raw.get("id"), "id", null));
        String command = requireText(raw.get("command"), "command", null);
        return new McpServerConfig(
                id,
                command,
                stringList(raw.get("args"), "args"),
                stringMap(raw.get("env"), "env"),
                bool(raw.get("enabled"), "enabled", true),
                seconds(raw.get("connectTimeoutSeconds"), "connectTimeoutSeconds",
                        DEFAULT_CONNECT_TIMEOUT_SECONDS),
                seconds(raw.get("callTimeoutSeconds"), "callTimeoutSeconds",
                        DEFAULT_CALL_TIMEOUT_SECONDS),
                new HashSet<String>(stringList(raw.get("readOnlyTools"), "readOnlyTools")));
    }

    /**
     * 获取服务标识。
     *
     * @return 标识
     */
    String id() {
        return id;
    }

    /**
     * 获取可执行文件。
     *
     * @return 命令名或路径
     */
    String command() {
        return command;
    }

    /**
     * 获取参数列表。
     *
     * @return 不可变参数列表，保证非 {@code null}
     */
    List<String> args() {
        return args;
    }

    /**
     * 获取追加的环境变量。
     * <p>
     * <b>它们是追加而不是替换</b>：MCP server 常常需要 PATH（否则连自己的解释器都找不到）与
     * 用户的 API key，因此子进程继承父进程环境，这里只叠加配置里写明的那几项。
     *
     * @return 不可变环境变量映射，保证非 {@code null}
     */
    Map<String, String> env() {
        return env;
    }

    /**
     * 判断是否启用。
     *
     * @return 启用返回 {@code true}
     */
    boolean enabled() {
        return enabled;
    }

    /**
     * 获取连接与握手超时秒数。
     *
     * @return 秒数
     */
    int connectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    /**
     * 获取单次调用超时秒数。
     *
     * @return 秒数
     */
    int callTimeoutSeconds() {
        return callTimeoutSeconds;
    }

    /**
     * 判断某个工具是否被用户显式声明为只读。
     *
     * @param toolName 工具原名
     * @return 只读返回 {@code true}
     */
    boolean isDeclaredReadOnly(String toolName) {
        return readOnlyTools.contains(toolName);
    }

    @Override
    public String toString() {
        return "McpServerConfig{id=" + id + ", command=" + command + ", args=" + args.size()
                + ", enabled=" + enabled + '}';
    }

    /**
     * 读取非空字符串。
     *
     * @param raw          配置原值，可为 {@code null}
     * @param key          配置键，用于报错
     * @param defaultValue 缺省值，可为 {@code null}（表示必填）
     * @return 配置文本
     * @throws JellyfishException 缺失或类型不符时抛出
     */
    private static String requireText(Object raw, String key, String defaultValue) {
        if (raw == null) {
            if (defaultValue == null) {
                throw new JellyfishException("mcp server 缺少必填项 " + key);
            }
            return defaultValue;
        }
        if (!(raw instanceof String) || ((String) raw).trim().isEmpty()) {
            throw new JellyfishException("mcp server 的 " + key + " 必须是非空字符串，实际为 " + raw);
        }
        return ((String) raw).trim();
    }

    /**
     * 读取字符串数组。
     *
     * @param raw 配置原值，可为 {@code null}
     * @param key 配置键，用于报错
     * @return 不可变字符串列表，保证非 {@code null}
     * @throws JellyfishException 结构非法时抛出
     */
    private static List<String> stringList(Object raw, String key) {
        if (raw == null) {
            return Collections.emptyList();
        }
        if (!(raw instanceof List)) {
            throw new JellyfishException("mcp server 的 " + key + " 必须是字符串数组，实际为 " + raw);
        }
        List<String> values = new ArrayList<String>();
        for (Object item : (List<?>) raw) {
            if (!(item instanceof String)) {
                throw new JellyfishException("mcp server 的 " + key + " 只能包含字符串，实际为 " + item);
            }
            values.add((String) item);
        }
        return Collections.unmodifiableList(values);
    }

    /**
     * 读取字符串映射。
     *
     * @param raw 配置原值，可为 {@code null}
     * @param key 配置键，用于报错
     * @return 不可变映射，保证非 {@code null}
     * @throws JellyfishException 结构非法时抛出
     */
    private static Map<String, String> stringMap(Object raw, String key) {
        if (raw == null) {
            return Collections.emptyMap();
        }
        if (!(raw instanceof Map)) {
            throw new JellyfishException("mcp server 的 " + key + " 必须是对象，实际为 " + raw);
        }
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String)) {
                throw new JellyfishException("mcp server 的 " + key + " 只能包含字符串键值");
            }
            values.put((String) entry.getKey(), (String) entry.getValue());
        }
        return Collections.unmodifiableMap(values);
    }

    /**
     * 读取布尔值。
     *
     * @param raw          配置原值，可为 {@code null}
     * @param key          配置键，用于报错
     * @param defaultValue 缺省值
     * @return 布尔值
     * @throws JellyfishException 类型不符时抛出
     */
    private static boolean bool(Object raw, String key, boolean defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (!(raw instanceof Boolean)) {
            throw new JellyfishException("mcp server 的 " + key + " 必须是布尔值，实际为 " + raw);
        }
        return ((Boolean) raw).booleanValue();
    }

    /**
     * 读取超时秒数：{@code 0} 是合法取值（表示不超时），负数与超过上限报错。
     *
     * @param raw          配置原值，可为 {@code null}
     * @param key          配置键，用于报错
     * @param defaultValue 缺省值
     * @return 秒数
     * @throws JellyfishException 取值非法时抛出
     */
    private static int seconds(Object raw, String key, int defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (!(raw instanceof Number)) {
            throw new JellyfishException("mcp server 的 " + key + " 必须是整数秒数，实际为 " + raw);
        }
        int value = ((Number) raw).intValue();
        if (value < 0 || value > MAX_TIMEOUT_SECONDS) {
            throw new JellyfishException("mcp server 的 " + key + " 必须在 0 到 "
                    + MAX_TIMEOUT_SECONDS + " 之间，实际为 " + value);
        }
        return value;
    }
}
