package zcd.jellyfish.plugin.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本插件的全局配置：把 {@code jellyfish.json} 里的
 * {@code plugins.configurations.jellyfish-mcp} 段解析成值对象。
 * <p>
 * <b>没有 {@code enabled} 开关</b>：插件的启停已经由 {@code plugins.enabled} 管了，
 * 再开一个只会让「为什么没生效」多一个可能的原因。单个 server 有自己的 {@code enabled}，
 * 因为「临时关掉某一个」是真实需求。
 * <p>
 * <b>{@code toolPrefix} 默认开</b>：MCP 工具名由 server 决定，而 server 之间、server 与内置工具之间
 * 完全可能重名（{@code read_file} 就是典型）。不加前缀等于让「谁先连上谁占坑」成为事实，
 * 而那种冲突的表现是「某个工具忽然变成了别人的」。关掉它只在「我确定没有重名且要短名字」时合理。
 * <p>
 * <b>名称长度上限默认 64</b>：这是各厂商对 function name 的共同上限，超了会被厂商拒绝，
 * 而拒绝发生在整次请求上——不是「这个工具不可用」，是「这一轮对话发不出去」。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class McpConfig {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(McpConfig.class);

    /** server 列表配置键。 */
    static final String KEY_SERVERS = "servers";

    /** 工具名前缀开关配置键。 */
    static final String KEY_TOOL_PREFIX = "toolPrefix";

    /** 工具名长度上限配置键。 */
    static final String KEY_TOOL_NAME_MAX_LENGTH = "toolNameMaxLength";

    /** 写类工具是否默认需要审批配置键。 */
    static final String KEY_ASK_WRITE_TOOLS = "askWriteTools";

    /** 启动期等待秒数配置键。 */
    static final String KEY_STARTUP_WAIT_SECONDS = "startupWaitSeconds";

    /** 单 server 工具数上限配置键。 */
    static final String KEY_MAX_TOOLS_PER_SERVER = "maxToolsPerServer";

    /** 缺省工具名前缀开关。 */
    static final boolean DEFAULT_TOOL_PREFIX = true;

    /** 缺省工具名长度上限。 */
    static final int DEFAULT_TOOL_NAME_MAX_LENGTH = 64;

    /** 缺省「写类工具需要审批」。 */
    static final boolean DEFAULT_ASK_WRITE_TOOLS = true;

    /** 缺省启动等待秒数：让第一轮就能看到工具，同时不把内核启动挂在网络上。 */
    static final int DEFAULT_STARTUP_WAIT_SECONDS = 5;

    /** 缺省单 server 工具数上限。 */
    static final int DEFAULT_MAX_TOOLS_PER_SERVER = 200;

    /** 工具名长度上限允许的最小值：再小就放不下前缀加哈希。 */
    static final int MIN_TOOL_NAME_MAX_LENGTH = 16;

    /** 工具名长度上限允许的最大值。 */
    static final int MAX_TOOL_NAME_MAX_LENGTH = 128;

    /** 启动等待秒数允许的最大值。 */
    static final int MAX_STARTUP_WAIT_SECONDS = 120;

    /** 单 server 工具数上限允许的最大值。 */
    static final int MAX_TOOLS_PER_SERVER_LIMIT = 2000;

    /** 全部 server，按配置顺序。 */
    private final List<McpServerConfig> servers;

    /** 是否给工具名加前缀。 */
    private final boolean toolPrefix;

    /** 工具名长度上限。 */
    private final int toolNameMaxLength;

    /** 写类工具是否默认需要审批。 */
    private final boolean askWriteTools;

    /** 启动期最多等待连接完成的秒数；{@code 0} 表示完全不等待。 */
    private final int startupWaitSeconds;

    /** 单 server 工具数上限。 */
    private final int maxToolsPerServer;

    /**
     * 构造配置。
     *
     * @param servers             全部 server
     * @param toolPrefix          是否给工具名加前缀
     * @param toolNameMaxLength   工具名长度上限
     * @param askWriteTools       写类工具是否默认需要审批
     * @param startupWaitSeconds  启动等待秒数
     * @param maxToolsPerServer   单 server 工具数上限
     */
    private McpConfig(List<McpServerConfig> servers, boolean toolPrefix, int toolNameMaxLength,
                      boolean askWriteTools, int startupWaitSeconds, int maxToolsPerServer) {
        this.servers = Collections.unmodifiableList(new ArrayList<McpServerConfig>(servers));
        this.toolPrefix = toolPrefix;
        this.toolNameMaxLength = toolNameMaxLength;
        this.askWriteTools = askWriteTools;
        this.startupWaitSeconds = startupWaitSeconds;
        this.maxToolsPerServer = maxToolsPerServer;
    }

    /**
     * 从插件配置段解析配置。
     *
     * @param configuration 插件配置段，可为 {@code null}
     * @return 配置值对象，保证非 {@code null}
     * @throws JellyfishException 配置结构或取值非法时抛出
     */
    static McpConfig from(Map<String, Object> configuration) {
        Map<String, Object> values = configuration == null
                ? Collections.<String, Object>emptyMap()
                : configuration;
        McpConfig config = new McpConfig(
                servers(values.get(KEY_SERVERS)),
                bool(values.get(KEY_TOOL_PREFIX), KEY_TOOL_PREFIX, DEFAULT_TOOL_PREFIX),
                boundedInt(values.get(KEY_TOOL_NAME_MAX_LENGTH), KEY_TOOL_NAME_MAX_LENGTH,
                        DEFAULT_TOOL_NAME_MAX_LENGTH, MIN_TOOL_NAME_MAX_LENGTH,
                        MAX_TOOL_NAME_MAX_LENGTH),
                bool(values.get(KEY_ASK_WRITE_TOOLS), KEY_ASK_WRITE_TOOLS, DEFAULT_ASK_WRITE_TOOLS),
                boundedInt(values.get(KEY_STARTUP_WAIT_SECONDS), KEY_STARTUP_WAIT_SECONDS,
                        DEFAULT_STARTUP_WAIT_SECONDS, 0, MAX_STARTUP_WAIT_SECONDS),
                boundedInt(values.get(KEY_MAX_TOOLS_PER_SERVER), KEY_MAX_TOOLS_PER_SERVER,
                        DEFAULT_MAX_TOOLS_PER_SERVER, 1, MAX_TOOLS_PER_SERVER_LIMIT));
        LOG.debug("mcp 插件配置: {}", config);
        return config;
    }

    /**
     * 获取全部 server。
     *
     * @return 不可变列表，保证非 {@code null}
     */
    List<McpServerConfig> servers() {
        return servers;
    }

    /**
     * 获取启用的 server。
     *
     * @return 不可变列表，保证非 {@code null}
     */
    List<McpServerConfig> enabledServers() {
        List<McpServerConfig> enabled = new ArrayList<McpServerConfig>();
        for (McpServerConfig server : servers) {
            if (server.enabled()) {
                enabled.add(server);
            }
        }
        return Collections.unmodifiableList(enabled);
    }

    /**
     * 判断是否给工具名加前缀。
     *
     * @return 加前缀返回 {@code true}
     */
    boolean toolPrefix() {
        return toolPrefix;
    }

    /**
     * 获取工具名长度上限。
     *
     * @return 字符数上限
     */
    int toolNameMaxLength() {
        return toolNameMaxLength;
    }

    /**
     * 判断写类工具是否默认需要审批。
     *
     * @return 需要审批返回 {@code true}
     */
    boolean askWriteTools() {
        return askWriteTools;
    }

    /**
     * 获取启动等待秒数。
     *
     * @return 秒数；{@code 0} 表示不等待
     */
    int startupWaitSeconds() {
        return startupWaitSeconds;
    }

    /**
     * 获取单 server 工具数上限。
     *
     * @return 条数上限
     */
    int maxToolsPerServer() {
        return maxToolsPerServer;
    }

    @Override
    public String toString() {
        return "McpConfig{servers=" + servers.size() + ", toolPrefix=" + toolPrefix
                + ", toolNameMaxLength=" + toolNameMaxLength + ", askWriteTools=" + askWriteTools
                + ", startupWaitSeconds=" + startupWaitSeconds
                + ", maxToolsPerServer=" + maxToolsPerServer + '}';
    }

    /**
     * 解析 server 列表，并校验标识唯一。
     *
     * @param raw 配置原值，可为 {@code null}
     * @return 不可变列表，保证非 {@code null}
     * @throws JellyfishException 结构非法或标识重复时抛出
     */
    @SuppressWarnings("unchecked")
    private static List<McpServerConfig> servers(Object raw) {
        if (raw == null) {
            return Collections.emptyList();
        }
        if (!(raw instanceof List)) {
            throw new JellyfishException(KEY_SERVERS + " 必须是数组，实际为 " + raw);
        }
        List<McpServerConfig> servers = new ArrayList<McpServerConfig>();
        Set<String> ids = new HashSet<String>();
        for (Object item : (List<Object>) raw) {
            if (!(item instanceof Map)) {
                throw new JellyfishException(KEY_SERVERS + " 的每一项都必须是对象，实际为 " + item);
            }
            McpServerConfig server = McpServerConfig.from((Map<String, Object>) item);
            if (!ids.add(server.id())) {
                throw new JellyfishException(KEY_SERVERS + " 里出现重复的 id: " + server.id());
            }
            servers.add(server);
        }
        return Collections.unmodifiableList(servers);
    }

    /**
     * 解析布尔配置。
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
            throw new JellyfishException(key + " 必须是布尔值，实际为 " + raw);
        }
        return ((Boolean) raw).booleanValue();
    }

    /**
     * 解析带上下界的整数配置。
     *
     * @param raw          配置原值，可为 {@code null}
     * @param key          配置键，用于报错
     * @param defaultValue 缺省值
     * @param min          允许的最小值（含）
     * @param max          允许的最大值（含）
     * @return 解析结果
     * @throws JellyfishException 类型不符或越界时抛出
     */
    private static int boundedInt(Object raw, String key, int defaultValue, int min, int max) {
        if (raw == null) {
            return defaultValue;
        }
        if (!(raw instanceof Number)) {
            throw new JellyfishException(key + " 必须是整数，实际为 " + raw);
        }
        int value = ((Number) raw).intValue();
        if (value < min || value > max) {
            throw new JellyfishException(key + " 必须在 " + min + " 到 " + max + " 之间，实际为 " + value);
        }
        return value;
    }
}
