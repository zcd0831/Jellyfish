package zcd.jellyfish.plugin.sessionfile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 本插件的配置解析：把 {@code jellyfish.json} 里的
 * {@code plugins.configurations.jellyfish-session-file} 段解析成值对象。
 * <p>
 * 两件由内核完成、这里<b>不再重复做</b>的事：项目级覆盖全局级、字符串值里的 {@code ${ENV}} 替换——
 * 拿到的就是最终值。但 {@code ~} <b>没有</b>被替换（内核只在配置文件的路径段上做展开），
 * 因此这里自己展开一次，否则默认值 {@code ~/jellyfish/sessions} 会被当成名为 {@code ~} 的目录。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class PluginConfig {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(PluginConfig.class);

    /** 会话目录配置键。 */
    static final String KEY_SESSION_DIR = "sessionDir";

    /** git 开关配置键。 */
    static final String KEY_GIT_ENABLED = "gitEnabled";

    /** 默认会话目录：与内核「全局级配置目录」同一处，会话是跨项目的运行态数据。 */
    static final String DEFAULT_SESSION_DIR = "~/jellyfish/sessions";

    /** 会话文件目录。 */
    private final Path sessionDirectory;

    /** 是否启用 git。 */
    private final boolean gitEnabled;

    /**
     * 构造配置。
     *
     * @param sessionDirectory 会话文件目录
     * @param gitEnabled       是否启用 git
     */
    private PluginConfig(Path sessionDirectory, boolean gitEnabled) {
        this.sessionDirectory = sessionDirectory;
        this.gitEnabled = gitEnabled;
    }

    /**
     * 从插件配置段解析配置。
     *
     * @param configuration 插件配置段，可为 {@code null}
     * @return 配置值对象
     * @throws JellyfishException 配置值类型不对或目录非法时抛出
     */
    static PluginConfig from(Map<String, Object> configuration) {
        Map<String, Object> values = configuration == null ? java.util.Collections.<String, Object>emptyMap()
                : configuration;
        return new PluginConfig(resolveDirectory(values.get(KEY_SESSION_DIR)),
                resolveBoolean(values.get(KEY_GIT_ENABLED), true));
    }

    /**
     * 获取会话文件目录。
     *
     * @return 会话文件目录
     */
    Path sessionDirectory() {
        return sessionDirectory;
    }

    /**
     * 判断是否启用 git。
     *
     * @return 启用返回 {@code true}
     */
    boolean gitEnabled() {
        return gitEnabled;
    }

    /**
     * 解析会话目录：缺省用默认目录，{@code ~} 展开为用户主目录。
     *
     * @param raw 配置原值，可为 {@code null}
     * @return 规范化的绝对路径
     * @throws JellyfishException 值不是字符串时抛出
     */
    private static Path resolveDirectory(Object raw) {
        if (raw == null) {
            return normalize(DEFAULT_SESSION_DIR);
        }
        if (!(raw instanceof String) || ((String) raw).trim().isEmpty()) {
            throw new JellyfishException(KEY_SESSION_DIR + " 必须是非空字符串，实际为 " + raw);
        }
        return normalize((String) raw);
    }

    /**
     * 解析布尔开关：兼容 JSON 布尔与字符串形式。
     *
     * @param raw      配置原值，可为 {@code null}
     * @param fallback 缺省值
     * @return 解析结果
     * @throws JellyfishException 值无法识别为布尔时抛出
     */
    private static boolean resolveBoolean(Object raw, boolean fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Boolean) {
            return (Boolean) raw;
        }
        String text = String.valueOf(raw).trim();
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        throw new JellyfishException(KEY_GIT_ENABLED + " 必须是布尔值，实际为 " + raw);
    }

    /**
     * 展开用户主目录前缀并规范化路径。
     *
     * @param raw 原始路径文本
     * @return 规范化绝对路径
     * @throws JellyfishException 无法确定用户主目录时抛出
     */
    private static Path normalize(String raw) {
        String text = raw.trim();
        if (text.startsWith("~")) {
            String home = System.getProperty("user.home");
            if (home == null || home.trim().isEmpty()) {
                throw new JellyfishException("无法展开 ~ ：未取到 user.home");
            }
            text = home + text.substring(1);
        }
        Path path = Paths.get(text).toAbsolutePath().normalize();
        LOG.debug("会话目录解析为: {}", path);
        return path;
    }
}
