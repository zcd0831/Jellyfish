package zcd.jellyfish.infra.support;

import org.apache.commons.lang3.StringUtils;

/**
 * 用户主目录占位符展开：把路径行首的 {@code ~} 换成 {@code user.home}。
 * <p>
 * 独立成工具类而不是留在 {@code SettingsReader} 里，是因为「配置里写的路径要展开 {@code ~}」这条语义
 * 不只适用于配置<b>文件</b>路径（{@code models.json} / {@code agents.json} 的双源路径）
 * 与配置<b>值</b>路径（{@code config.json} 里的插件扫描目录），两处必须用同一套规则，
 * 否则「同一个 {@code ~} 在两个配置里行为不同」会成为长期陷阱。
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
        String home = System.getProperty("user.home");
        if (StringUtils.isBlank(home)) {
            return path;
        }
        return path.length() == 1 ? home : home + path.substring(1);
    }
}
