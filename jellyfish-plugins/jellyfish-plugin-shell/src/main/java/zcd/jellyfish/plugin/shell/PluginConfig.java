package zcd.jellyfish.plugin.shell;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.event.notification.ConfigWarningEvent;
import zcd.jellyfish.api.plugin.PluginContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本插件的配置：把 {@code jellyfish.json} 里的
 * {@code plugins.configurations.jellyfish-shell} 段解析成值对象。
 * <p>
 * 项目级覆盖全局级、字符串值里的 {@code ${ENV}} 替换都由内核完成，这里拿到的就是最终值。
 * <p>
 * <b>非法值一律回退缺省并告警，不抛异常</b>：配置写错不该让 shell 工具整个消失——那会把
 * 「一个参数写成了字符串」升级成「模型忽然没有命令行能力」。告警走 {@link ConfigWarningEvent}
 * 而不是只记日志：这个插件在 {@code -tui} 下运行时，用户要能在界面上看到它。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class PluginConfig {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(PluginConfig.class);

    /** 配置来源，用于告警定位。 */
    private static final String SOURCE = "plugins.configurations.jellyfish-shell";

    /** 缺省墙上时钟超时（秒）。 */
    static final int DEFAULT_TIMEOUT_SECONDS = 120;

    /** 缺省墙钟超时上限（秒）。 */
    static final int DEFAULT_MAX_TIMEOUT_SECONDS = 1800;

    /** 超时上限的硬边界：再大就等于没有上限了。 */
    static final int HARD_MAX_TIMEOUT_SECONDS = 24 * 3600;

    /** 缺省静默超时（秒），{@code 0} 表示关闭。 */
    static final int DEFAULT_IDLE_TIMEOUT_SECONDS = 0;

    /**
     * 默认剔除的环境变量名模式（大小写不敏感的通配匹配）。
     * <p>
     * 这些名字覆盖了绝大多数凭据载体，而它们的值一旦进入工具输出就会被送到远端 LLM。
     */
    static final List<String> DEFAULT_SENSITIVE_PATTERNS = Collections.unmodifiableList(Arrays.asList(
            "*KEY*", "*TOKEN*", "*SECRET*", "*PASSWORD*", "*CREDENTIAL*"));

    /** 墙上时钟超时（秒）。 */
    private final int timeoutSeconds;

    /** 墙钟超时上限（秒）。 */
    private final int maxTimeoutSeconds;

    /** 静默超时（秒），{@code 0} 表示关闭。 */
    private final int idleTimeoutSeconds;

    /** 额外注入或覆盖的环境变量。 */
    private final Map<String, String> environment;

    /** 需要从子进程环境里剔除的名字模式。 */
    private final List<String> sensitivePatterns;

    /** 前缀白名单，空列表表示不启用。 */
    private final List<String> allowedCommands;

    /** 命令分类策略。 */
    private final CommandPolicy commandPolicy;

    /**
     * 构造配置。
     *
     * @param timeoutSeconds     墙上时钟超时（秒）
     * @param maxTimeoutSeconds  墙钟超时上限（秒）
     * @param idleTimeoutSeconds 静默超时（秒）
     * @param environment        额外环境变量
     * @param sensitivePatterns  剔除模式
     * @param allowedCommands    前缀白名单
     * @param commandPolicy      命令分类策略
     */
    private PluginConfig(int timeoutSeconds, int maxTimeoutSeconds, int idleTimeoutSeconds,
                         Map<String, String> environment, List<String> sensitivePatterns,
                         List<String> allowedCommands, CommandPolicy commandPolicy) {
        this.timeoutSeconds = timeoutSeconds;
        this.maxTimeoutSeconds = maxTimeoutSeconds;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.environment = environment;
        this.sensitivePatterns = sensitivePatterns;
        this.allowedCommands = allowedCommands;
        this.commandPolicy = commandPolicy;
    }

    /**
     * 从插件配置段解析配置。
     * <p>
     * {@code context} 只用于发告警事件；为 {@code null} 时降级为只记日志（单测里不必造上下文）。
     *
     * @param context 插件上下文，可为 {@code null}
     * @return 配置值对象
     */
    static PluginConfig from(PluginContext context) {
        Map<String, Object> values = context == null ? Collections.<String, Object>emptyMap()
                : safeConfiguration(context);
        List<String> allowed = stringListOf(context, values.get("allowedCommands"), "allowedCommands");
        return new PluginConfig(
                positiveInt(context, values, "timeoutSeconds", DEFAULT_TIMEOUT_SECONDS,
                        HARD_MAX_TIMEOUT_SECONDS),
                positiveInt(context, values, "maxTimeoutSeconds", DEFAULT_MAX_TIMEOUT_SECONDS,
                        HARD_MAX_TIMEOUT_SECONDS),
                nonNegativeInt(context, values, "idleTimeoutSeconds", DEFAULT_IDLE_TIMEOUT_SECONDS,
                        HARD_MAX_TIMEOUT_SECONDS),
                textMap(context, values.get("environment"), "environment"),
                stringListOf(context, values.get("sensitivePatterns"), "sensitivePatterns"),
                allowed,
                CommandPolicy.from(context, values.get("commandPolicy"), allowed));
    }

    /**
     * 取插件配置段，取不到时当作空配置。
     *
     * @param context 插件上下文
     * @return 配置段，保证非 {@code null}
     */
    private static Map<String, Object> safeConfiguration(PluginContext context) {
        try {
            Map<String, Object> configuration = context.configuration();
            return configuration == null ? Collections.<String, Object>emptyMap() : configuration;
        } catch (RuntimeException e) {
            // 配置段取不到不是本插件能修的问题，但也不能让它变成「插件启动失败」
            LOG.warn("读取 shell 插件配置失败，按缺省值启动: {}", e.toString());
            return Collections.<String, Object>emptyMap();
        }
    }

    /**
     * 读取正整数配置，非法值回退缺省并告警。
     *
     * @param context 插件上下文，可为 {@code null}
     * @param values  配置段
     * @param key     键名
     * @param fallback 缺省值
     * @param max     允许的最大值
     * @return 有效值
     */
    private static int positiveInt(PluginContext context, Map<String, Object> values, String key,
                                   int fallback, int max) {
        int value = nonNegativeInt(context, values, key, fallback, max);
        if (value <= 0) {
            warn(context, key + " 必须大于 0，已按缺省值 " + fallback + " 处理");
            return fallback;
        }
        return value;
    }

    /**
     * 读取非负整数配置，非法值回退缺省并告警。
     *
     * @param context 插件上下文，可为 {@code null}
     * @param values  配置段
     * @param key     键名
     * @param fallback 缺省值
     * @param max     允许的最大值
     * @return 有效值
     */
    private static int nonNegativeInt(PluginContext context, Map<String, Object> values, String key,
                                      int fallback, int max) {
        Object raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        Integer parsed = asInt(raw);
        if (parsed == null || parsed < 0) {
            warn(context, key + " 不是非负整数（" + raw + "），已按缺省值 " + fallback + " 处理");
            return fallback;
        }
        if (parsed > max) {
            warn(context, key + "=" + parsed + " 超过上限 " + max + "，已按上限处理");
            return max;
        }
        return parsed;
    }

    /**
     * 把配置值解析成整数。
     *
     * @param raw 原始值
     * @return 整数；无法解析时返回 {@code null}
     */
    private static Integer asInt(Object raw) {
        if (raw instanceof Number) {
            return Integer.valueOf(((Number) raw).intValue());
        }
        try {
            return Integer.valueOf(Integer.parseInt(String.valueOf(raw).trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 读取字符串映射配置，非映射项被丢弃并告警。
     *
     * @param context 插件上下文，可为 {@code null}
     * @param raw     原始值
     * @param key     键名
     * @return 映射，保证非 {@code null}
     */
    private static Map<String, String> textMap(PluginContext context, Object raw, String key) {
        if (raw == null) {
            return Collections.emptyMap();
        }
        if (!(raw instanceof Map)) {
            warn(context, key + " 必须是对象，实际是 " + raw);
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 读取字符串列表配置，非字符串项被丢弃并告警。
     *
     * @param context 插件上下文，可为 {@code null}
     * @param raw     原始值
     * @param key     键名
     * @return 列表，保证非 {@code null}
     */
    static List<String> stringListOf(PluginContext context, Object raw, String key) {
        if (raw == null) {
            return Collections.emptyList();
        }
        if (!(raw instanceof List)) {
            warn(context, key + " 必须是数组，实际是 " + raw);
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>();
        for (Object item : (List<?>) raw) {
            if (item == null) {
                continue;
            }
            String text = String.valueOf(item).trim();
            if (text.isEmpty()) {
                continue;
            }
            result.add(text);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 发出配置告警：事件给界面，日志给事后排查。
     *
     * @param context 插件上下文，可为 {@code null}
     * @param message 告警描述
     */
    private static void warn(PluginContext context, String message) {
        LOG.warn("shell 插件配置告警: {}", message);
        if (context == null) {
            return;
        }
        try {
            context.emit(new ConfigWarningEvent(SOURCE, message));
        } catch (RuntimeException e) {
            // 告警通道本身失败不该让配置解析失败
            LOG.debug("发布配置告警事件失败: {}", e.toString());
        }
    }

    /**
     * 获取墙上时钟超时（秒）。
     *
     * @return 超时秒数
     */
    int timeoutSeconds() {
        return timeoutSeconds;
    }

    /**
     * 获取墙钟超时上限（秒）。
     *
     * @return 上限秒数
     */
    int maxTimeoutSeconds() {
        return maxTimeoutSeconds;
    }

    /**
     * 获取静默超时（秒）。
     *
     * @return 秒数，{@code 0} 表示关闭
     */
    int idleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    /**
     * 获取额外环境变量。
     *
     * @return 映射，保证非 {@code null}
     */
    Map<String, String> environment() {
        return environment;
    }

    /**
     * 获取用户额外声明的剔除模式。
     * <p>
     * 注意语义是「<b>追加</b>在内置表之上」而不是「替换」：内置那几条覆盖的是凭据的通用命名，
     * 用户替换掉它们不会得到任何好处，只会让默认安全性取决于他配了几条。
     *
     * @return 追加模式列表，保证非 {@code null}
     */
    List<String> extraSensitivePatterns() {
        return sensitivePatterns;
    }

    /**
     * 获取前缀白名单。
     *
     * @return 白名单，空列表表示不启用
     */
    List<String> allowedCommands() {
        return allowedCommands;
    }

    /**
     * 获取命令分类策略。
     *
     * @return 策略，保证非 {@code null}
     */
    CommandPolicy commandPolicy() {
        return commandPolicy;
    }
}
