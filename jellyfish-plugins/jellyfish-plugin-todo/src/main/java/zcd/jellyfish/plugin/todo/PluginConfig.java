package zcd.jellyfish.plugin.todo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;

/**
 * 本插件的配置解析：把 {@code jellyfish.json} 里的
 * {@code plugins.configurations.jellyfish-todo} 段解析成值对象。
 * <p>
 * 项目级覆盖全局级、字符串值里的 {@code ${ENV}} 替换都由内核完成，这里拿到的就是最终值；
 * 但 {@code ~} <b>没有</b>被展开（内核只在配置文件的路径段上做这件事），因此这里自己展开一次，
 * 否则默认值 {@code ~/jellyfish/todos} 会被当成名为 {@code ~} 的目录。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class PluginConfig {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(PluginConfig.class);

    /** 待办目录配置键。 */
    static final String KEY_TODO_DIR = "todoDir";

    /** 默认待办目录：与内核「全局级配置目录」同一处，待办是跨项目的运行态数据。 */
    static final String DEFAULT_TODO_DIR = "~/jellyfish/todos";

    /** 待办文件目录。 */
    private final Path todoDirectory;

    /**
     * 构造配置。
     *
     * @param todoDirectory 待办文件目录
     */
    private PluginConfig(Path todoDirectory) {
        this.todoDirectory = todoDirectory;
    }

    /**
     * 从插件配置段解析配置。
     *
     * @param configuration 插件配置段，可为 {@code null}
     * @return 配置值对象
     * @throws JellyfishException 配置值类型不对或目录非法时抛出
     */
    static PluginConfig from(Map<String, Object> configuration) {
        Map<String, Object> values = configuration == null ? Collections.<String, Object>emptyMap()
                : configuration;
        return new PluginConfig(resolveDirectory(values.get(KEY_TODO_DIR)));
    }

    /**
     * 获取待办文件目录。
     *
     * @return 待办文件目录
     */
    Path todoDirectory() {
        return todoDirectory;
    }

    /**
     * 解析待办目录：缺省用默认目录，{@code ~} 展开为用户主目录。
     *
     * @param raw 配置原值，可为 {@code null}
     * @return 规范化的绝对路径
     * @throws JellyfishException 值不是字符串时抛出
     */
    private static Path resolveDirectory(Object raw) {
        if (raw == null) {
            return normalize(DEFAULT_TODO_DIR);
        }
        if (!(raw instanceof String) || ((String) raw).trim().isEmpty()) {
            throw new JellyfishException(KEY_TODO_DIR + " 必须是非空字符串，实际为 " + raw);
        }
        return normalize((String) raw);
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
        LOG.debug("待办目录解析为: {}", path);
        return path;
    }
}
