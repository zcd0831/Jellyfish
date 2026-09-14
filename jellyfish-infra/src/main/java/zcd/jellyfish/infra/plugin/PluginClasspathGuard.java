package zcd.jellyfish.infra.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * 插件 classpath 护栏：阻止插件自带内核契约与 PF4J。
 * <p>
 * 存在的理由是一条类加载事实：PF4J 的插件类加载器默认是<b>子优先</b>（{@code ClassLoadingStrategy.PDA}），
 * 插件包里一旦带上 {@code zcd/jellyfish/api} 或 {@code org/pf4j}，父加载器侧的同名类就会被遮蔽，
 * 结果是 `JellyfishPlugin` / `PluginContext` 出现两份互不相识的类型，报错却表现为莫名的
 * {@code ClassCastException}。
 * <p>
 * 因此把校验放在<b>加载期</b>并给出可读原因：宁可插件因一条明确的错误不启动，也不要等到
 * 启动或调用时才炸出难以归因的类型错误。
 *
 * @author zcd
 */
final class PluginClasspathGuard {

    /** 内核契约在插件包内的条目前缀。 */
    private static final String API_ENTRY_PREFIX = "zcd/jellyfish/api/";

    /** PF4J 在插件包内的条目前缀。 */
    private static final String PF4J_ENTRY_PREFIX = "org/pf4j/";

    /**
     * 工具类，禁止实例化。
     */
    private PluginClasspathGuard() {
    }

    /**
     * 检查插件内容，把违规条目记入描述符。
     * <p>
     * 只记问题、不抛异常：PF4J 的加载循环只捕获 {@code PluginRuntimeException}，
     * 在这里抛业务异常会把「一个插件的打包错误」放大成「整批插件加载失败」。
     *
     * @param pluginPath 插件文件或目录
     * @param descriptor 插件描述符
     */
    static void check(Path pluginPath, JellyfishPluginDescriptor descriptor) {
        Set<String> packagedPrefixes = new LinkedHashSet<>();
        try {
            if (Files.isDirectory(pluginPath)) {
                collectFromDirectory(pluginPath, packagedPrefixes);
            } else {
                collectFromArchive(pluginPath, packagedPrefixes);
            }
        } catch (IOException e) {
            descriptor.addLoadError("无法检查插件内容: " + pluginPath.getFileName() + " (" + e.getMessage() + ')');
            return;
        }
        for (String prefix : packagedPrefixes) {
            descriptor.addLoadError("插件不得自带 " + prefix
                    + "：该类由父加载器提供，自带会破坏类型同一性（请改为 provided 依赖）");
        }
    }

    /**
     * 扫描展开目录（PF4J 会把 zip / jar 展开后交给解析器）。
     *
     * @param pluginPath 插件目录
     * @param target     收集命中的条目前缀
     * @throws IOException 遍历失败时抛出
     */
    private static void collectFromDirectory(Path pluginPath, Set<String> target) throws IOException {
        try (Stream<Path> paths = Files.walk(pluginPath)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                inspect(pluginPath.relativize(path).toString().replace('\\', '/'), target);
            }
        }
    }

    /**
     * 扫描压缩包。
     *
     * @param pluginPath 插件压缩包
     * @param target     收集命中的条目前缀
     * @throws IOException 读取失败时抛出
     */
    private static void collectFromArchive(Path pluginPath, Set<String> target) throws IOException {
        try (JarFile jarFile = new JarFile(pluginPath.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                inspect(entries.nextElement().getName(), target);
            }
        }
    }

    /**
     * 判断单个条目是否属于禁止自带的包。
     *
     * @param entry  条目路径，使用 {@code /} 分隔
     * @param target 收集命中的条目前缀
     */
    private static void inspect(String entry, Set<String> target) {
        if (entry.startsWith(API_ENTRY_PREFIX)) {
            target.add(API_ENTRY_PREFIX);
        } else if (entry.startsWith(PF4J_ENTRY_PREFIX)) {
            target.add(PF4J_ENTRY_PREFIX);
        }
    }
}
