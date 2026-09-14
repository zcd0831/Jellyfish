package zcd.jellyfish.infra.config;

import org.apache.commons.lang3.StringUtils;
import zcd.jellyfish.api.JellyfishException;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 只读的配置文件读取器：把 classpath 资源或本地文件读成原始文本。
 * <p>
 * 只负责「路径 → 文本」；环境变量占位符的替换由 {@link SettingsBinder} 在解析后进行，
 * 因此这里不做任何内容加工。唯一例外是行首的 `~`：那是文件系统语义而非内容加工，
 * 由 {@link #expandHome(String)} 在这里展开。
 * <p>
 * 设计为只读且无写入能力：运行时不允许回写用户配置，apiKey 等敏感值通过环境变量注入而非落盘。
 * 以可注入的实例形式提供，便于在测试中替换读取行为。
 *
 * @author zcd
 */
@Singleton
public class SettingsReader {

    /** classpath 路径前缀。 */
    private static final String CLASSPATH_PREFIX = "classpath:";

    /** 用户主目录占位前缀（仅支持 `~` 与 `~/` / `~\` 两种形式）。 */
    private static final String HOME_PREFIX = "~";

    private static final int BUFFER_SIZE = 1024;

    /**
     * 无状态读取器，构造器仅供 Dagger 注入。
     */
    @Inject
    public SettingsReader() {
    }

    /**
     * 读取配置文件原始文本。
     *
     * @param path 配置文件路径，支持 {@code classpath:xxx} 与本地文件路径；为空时返回 {@code null}
     * @return 文件内容；路径为空或文件不存在时返回 {@code null}
     * @throws JellyfishException 路径指向目录或读取失败时抛出
     */
    public String read(String path) {
        if (StringUtils.isBlank(path)) {
            return null;
        }
        return path.startsWith(CLASSPATH_PREFIX)
                ? readResourceContent(path)
                : readFileContent(path);
    }

    /**
     * 读取本地文件内容。
     * <p>
     * 不做 {@code Files.exists} 预检，直接用一次读取 + 捕获 {@link NoSuchFileException}，
     * 避免「检查后文件消失」的竞态。
     *
     * @param path 本地文件路径，行首的 {@code ~} 会被展开为用户主目录
     * @return 文件内容；文件不存在时返回 {@code null}
     * @throws JellyfishException 路径为目录或读取失败时抛出
     */
    private String readFileContent(String path) {
        Path filePath = Paths.get(expandHome(path));
        if (Files.isDirectory(filePath)) {
            throw new JellyfishException("settings path is a directory: " + path);
        }
        try {
            return new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new JellyfishException("failed to read settings file: " + path, e);
        }
    }

    /**
     * 展开行首的 {@code ~} 为用户主目录。
     * <p>
     * 只认 {@code ~} 与 {@code ~/}（或 {@code ~\}）两种形式：{@code ~other/x.json} 需要解析其他用户的
     * 主目录，那是 shell 的能力，内核不猜——原样交给 {@link Paths} 当作相对路径处理。
     * {@code user.home} 缺失（极端受限的运行环境）时同样原样返回，不把路径改坏。
     *
     * @param path 原始路径
     * @return 展开后的路径；无需展开时原样返回
     */
    private static String expandHome(String path) {
        if (!path.startsWith(HOME_PREFIX)) {
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

    /**
     * 读取 classpath 资源内容。
     *
     * @param path 以 {@code classpath:} 开头的资源路径
     * @return 资源内容；资源不存在时返回 {@code null}
     */
    private String readResourceContent(String path) {
        String resourcePath = path.substring(CLASSPATH_PREFIX.length());
        ClassLoader classLoader = SettingsReader.class.getClassLoader();
        try (InputStream inputStream = classLoader.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                return null;
            }
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            int len = inputStream.read(buffer);
            while (len != -1) {
                outputStream.write(buffer, 0, len);
                len = inputStream.read(buffer);
            }
            return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JellyfishException("failed to read settings resource: " + path, e);
        }
    }
}
