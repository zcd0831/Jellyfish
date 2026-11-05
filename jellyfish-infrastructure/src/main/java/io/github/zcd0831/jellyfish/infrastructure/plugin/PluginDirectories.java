package io.github.zcd0831.jellyfish.infrastructure.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * `plugins/&lt;id&gt;/` 的目录名必须与 jar 里声明的插件 id 一致，否则该目录一律不装载。
 * <p>
 * 为什么必须真的不装载（而不只是打印一行告警）：同一 id 落在两个目录里，PF4J 认为是同一个插件，
 * 卸载时只删掉自己认识的那个 ⇒ 另一个目录的 jar 成了漏网的残留。
 * 规则出处：09-infrastructure §9.7（plugins/&lt;id&gt;/ 目录名与 jar 内 Plugin-Id 不一致 ⇒ 告警并忽略该目录）。
 */
public final class PluginDirectories {

    /** 目录里有个可识别的 jar，且它声明的 id 与目录名不符。 */
    public static boolean mismatched(Path pluginDir) {
        String declared = declaredId(pluginDir);
        return declared != null && !declared.equals(pluginDir.getFileName().toString());
    }

    /** 目录里认不出任何插件 id（空目录、坏 jar）：这种目录同样不该装载。 */
    public static boolean unrecognizable(Path pluginDir) {
        return declaredId(pluginDir) == null;
    }

    /**
     * jar 里声明的插件 id：先看 `plugin.properties` 的 `plugin.id`（PF4J 的属性描述符），
     * 再看清单的 `Plugin-Id`（两者 PF4J 都认，我们按同样顺序取）。
     * 认不出返回 null。
     */
    public static String declaredId(Path pluginDir) {
        if (!Files.isDirectory(pluginDir)) {
            return null;
        }
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(pluginDir, "*.jar")) {
            for (Path jar : jars) {
                String id = declaredIdIn(jar);
                if (id != null) {
                    return id;
                }
            }
        } catch (IOException e) {
            return null;   // 目录读不了：当作认不出，不装载
        }
        return null;
    }

    private static String declaredIdIn(Path jar) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            String fromProperties = fromProperties(jarFile);
            if (fromProperties != null) {
                return fromProperties;
            }
            Manifest manifest = jarFile.getManifest();
            if (manifest == null) {
                return null;
            }
            String id = manifest.getMainAttributes().getValue("Plugin-Id");
            return id == null || id.trim().isEmpty() ? null : id.trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static String fromProperties(JarFile jarFile) throws IOException {
        java.util.zip.ZipEntry entry = jarFile.getEntry("plugin.properties");
        if (entry == null) {
            return null;
        }
        Properties properties = new Properties();
        try (InputStream in = jarFile.getInputStream(entry)) {
            properties.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        String id = properties.getProperty("plugin.id");
        return id == null || id.trim().isEmpty() ? null : id.trim();
    }

    private PluginDirectories() {
    }
}
