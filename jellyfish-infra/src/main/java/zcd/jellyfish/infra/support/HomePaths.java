package zcd.jellyfish.infra.support;

import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 用户主目录占位符的展开与缩写：{@code ~} 与 {@code user.home} 之间的两个方向。
 * <p>
 * 独立成工具类而不是留在 {@code SettingsReader} 里，是因为「配置里写的路径要展开 {@code ~}」这条语义
 * 不只适用于配置<b>文件</b>路径（{@code models.json} / {@code agents.json} 的双源路径）
 * 与配置<b>值</b>路径（{@code config.json} 里的插件扫描目录），两处必须用同一套规则，
 * 否则「同一个 {@code ~} 在两个配置里行为不同」会成为长期陷阱。反向的 {@link #abbreviate(Path)}
 * 放在同一个类里是同一个理由：一对互逆变换的规则分开维护，迟早出现「展开认这个、缩写认那个」。
 * <p>
 * 只认 {@code ~} 与 {@code ~/}（或 {@code ~\}）两种形式：{@code ~other/x.json} 需要解析其他用户的
 * 主目录，那是 shell 的能力，内核不猜——原样交给 {@link java.nio.file.Paths} 当作相对路径处理。
 * {@code user.home} 缺失（极端受限的运行环境）时同样原样返回，不把路径改坏。
 *
 * @author zcd
 */
public final class HomePaths {

    /** 用户主目录占位前缀。 */
    private static final String HOME_PREFIX = "~";

    /**
     * 常量类，禁止实例化。
     */
    private HomePaths() {
    }

    /**
     * 展开路径行首的 {@code ~} 为用户主目录。
     *
     * @param path 原始路径，可为 {@code null}
     * @return 展开后的路径；无需展开时原样返回，入参为 {@code null} 时返回 {@code null}
     */
    public static String expand(String path) {
        if (path == null || !path.startsWith(HOME_PREFIX)) {
            return path;
        }
        if (path.length() > 1) {
            char next = path.charAt(1);
            if (next != '/' && next != '\\') {
                return path;
            }
        }
        Path home = homeDirectory();
        if (home == null) {
            return path;
        }
        String homeText = home.toString();
        return path.length() == 1 ? homeText : homeText + path.substring(1);
    }

    /**
     * 把主目录内的绝对路径缩写成 {@code ~} 开头的形式。
     * <p>
     * <b>与 {@link #expand(String)} 是一对互逆变换，因此必须放在同一个类里</b>：工具结果的落盘路径
     * 会随封送回灌进模型上下文（并随会话落盘、可能被导出或转发给上游模型），而绝对路径里的真实用户名
     * 与主目录结构对「回查这个文件」这件事毫无用处。缩写之后，模型拿到的仍是一个可以直接交给
     * {@code read_file} 的路径（文件工具认 {@code ~}），但上下文里不再出现本机的用户布局。
     * <p>
     * <b>规则与 {@code expand} 严格对称</b>：只缩「落在主目录内」的路径，其余原样返回绝对路径。
     * 生成的形式恒为 {@code ~} 加一个分隔符再加相对部分，因此对已经缩过的文本重复调用是幂等的
     * ——调用点分布在三条返回路径上（事后截断、捕获期溢出、子代理归档），幂等让它们不必关心顺序。
     *
     * @param path 待缩写的路径，可为 {@code null}
     * @return {@code ~} 开头的短路径；主目录外、主目录不可知或入参为 {@code null} 时返回规范化的绝对路径
     */
    public static String abbreviate(Path path) {
        if (path == null) {
            return null;
        }
        Path absolute = path.toAbsolutePath().normalize();
        Path home = homeDirectory();
        if (home == null || !absolute.startsWith(home)) {
            return absolute.toString();
        }
        Path relative = home.relativize(absolute);
        return relative.toString().isEmpty() ? HOME_PREFIX : HOME_PREFIX + File.separator + relative;
    }

    /**
     * 取规范化后的用户主目录。
     *
     * @return 主目录；{@code user.home} 缺失（极端受限的运行环境）时返回 {@code null}
     */
    private static Path homeDirectory() {
        String home = System.getProperty("user.home");
        return StringUtils.isBlank(home) ? null : Paths.get(home).toAbsolutePath().normalize();
    }
}
