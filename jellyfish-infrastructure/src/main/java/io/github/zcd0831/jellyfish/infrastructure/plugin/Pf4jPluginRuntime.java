package io.github.zcd0831.jellyfish.infrastructure.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zcd0831.jellyfish.application.shared.TechnicalLog;
import io.github.zcd0831.jellyfish.application.shared.port.PluginDescriptor;
import io.github.zcd0831.jellyfish.application.shared.port.PluginRuntimePort;
import io.github.zcd0831.jellyfish.domain.action.tool.SideEffectLevel;
import io.github.zcd0831.jellyfish.domain.action.tool.SourceType;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolDefinition;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolSchema;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolSource;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolStatus;
import io.github.zcd0831.jellyfish.domain.shared.PluginId;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;
import io.github.zcd0831.jellyfish.infrastructure.file.JsonCodec;
import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginStatusProvider;
import org.pf4j.PluginWrapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * PF4J 3.16.0（Java 8 字节码）。装/启/停/卸都是运行期动作；同 id 升级要重启。
 * 规则出处：09-infrastructure §9.12。
 */
public final class Pf4jPluginRuntime implements PluginRuntimePort {

    static final String TOOLS_PREFIX = "META-INF/jellyfish/tools/";

    /** 宿主已提供的依赖：插件再打一份就会在类加载器里打架。 */
    private static final String[] HOST_PACKAGES = {
            "org/apache/logging/log4j/", "com/fasterxml/jackson/", "okhttp3/", "okio/",
            "com/networknt/", "org/pf4j/", "com/knuddels/jtokkit/", "org/slf4j/"};

    private final Path pluginsDir;
    private final JsonCodec codec;
    private final Map<String, Class<?>> capabilityTypes;
    private final JellyfishPluginManager manager;
    private final java.util.Set<String> loadedJars = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * 装载失败过的 jar：路径 → 当时的最后修改时间。同一份坏文件不反复重试，
     * 但文件一改（重新下载/替换）就再试一次，不必重启。
     */
    private final Map<String, Long> failedJars = new java.util.concurrent.ConcurrentHashMap<String, Long>();

    public Pf4jPluginRuntime(Path pluginsDir, JsonCodec codec, Map<String, Class<?>> capabilityTypes) {
        this.pluginsDir = pluginsDir;
        this.codec = codec;
        this.capabilityTypes = capabilityTypes;
        this.manager = new JellyfishPluginManager(pluginsDir);
    }

    @Override
    public List<PluginDescriptor> discover() {
        sync();
        List<PluginDescriptor> out = new ArrayList<PluginDescriptor>();
        for (PluginWrapper wrapper : manager.getPlugins()) {
            out.add(new PluginDescriptor(PluginId.of(wrapper.getPluginId()),
                    wrapper.getDescriptor().getVersion(), apiVersionOf(wrapper),
                    wrapper.getPluginState() == PluginState.STARTED));
        }
        return out;
    }

    @Override
    public List<ToolDefinition> toolsOf(PluginId pluginId) {
        sync();
        PluginWrapper wrapper = manager.getPlugin(pluginId.getValue());
        if (wrapper == null) {
            return Collections.emptyList();
        }
        List<ToolDefinition> out = new ArrayList<ToolDefinition>();
        try {
            Path path = wrapper.getPluginPath();
            if (Files.isDirectory(path)) {
                Path toolsDir = path.resolve(TOOLS_PREFIX);
                if (Files.isDirectory(toolsDir)) {
                    try (java.nio.file.DirectoryStream<Path> files =
                                 Files.newDirectoryStream(toolsDir, "*.json")) {
                        for (Path file : files) {
                            out.add(toDomain(pluginId, read(file)));
                        }
                    }
                }
            } else {
                try (ZipFile zip = new ZipFile(path.toFile())) {
                    java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (!entry.isDirectory() && entry.getName().startsWith(TOOLS_PREFIX)
                                && entry.getName().endsWith(".json")) {
                            out.add(toDomain(pluginId, new String(read(zip, entry), StandardCharsets.UTF_8)));
                        }
                    }
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("读取插件工具声明失败：" + pluginId.getValue(), e);
        }
    }

    @Override
    public void start(PluginId pluginId) {
        sync();
        manager.startPlugin(pluginId.getValue());
    }

    @Override
    public void stop(PluginId pluginId) {
        manager.stopPlugin(pluginId.getValue());
    }

    /**
     * 自己扫 `plugins/<id>/*.jar` 再逐个交给 PF4J：布局由我们定义，PF4J 只负责解析与装载。
     * 每次取用前重扫 ⇒ 装上新 jar 后无需重启就能被发现。
     * <p>
     * 三条纪律（09-infrastructure §9.12、05-action §5.8.6「单个插件失败不牵连」）：<br>
     * ① 目录流一律 try-with-resources：每次取用都重扫，漏关就是每轮泄漏几个 fd，攒到上限整个插件体系崩；<br>
     * ② 一个 jar 装载失败只记它自己：坏包抛的是 {@link java.util.zip.ZipError}（Error，不是异常），
     *    `catch (RuntimeException)` 接不住，会连带健康插件一起永久不可见；<br>
     * ③ 先装载成功再登记：坏 jar 若先进 loadedJars，修好后也不会重装，只能重启。
     */
    private void sync() {
        if (!Files.isDirectory(pluginsDir)) {
            return;
        }
        for (Path pluginDir : pluginDirectories()) {
            // 目录名与 jar 内 id 不一致（或认不出 id）⇒ 整个目录不装载：
            // 同一 id 落在两个目录里时，卸载只会删掉自己认识的那个（09 §9.7）
            if (PluginDirectories.mismatched(pluginDir) || PluginDirectories.unrecognizable(pluginDir)) {
                TechnicalLog.event("plugin.dir_ignored", 0L, "dir=" + pluginDir.getFileName(),
                        "declared=" + PluginDirectories.declaredId(pluginDir));
                continue;
            }
            for (Path jar : jarsIn(pluginDir)) {
                loadOnce(jar);
            }
        }
    }

    private void loadOnce(Path jar) {
        String key = jar.toAbsolutePath().toString();
        if (loadedJars.contains(key)) {
            return;
        }
        long modified = lastModified(jar);
        Long failedAt = failedJars.get(key);
        if (failedAt != null && failedAt.longValue() == modified) {
            return;   // 同一份坏文件不重试；改过了才再试
        }
        try {
            manager.loadPlugin(jar);
            loadedJars.add(key);
            failedJars.remove(key);
        } catch (Throwable failure) {   // 含 ZipError / NoClassDefFoundError：坏包不能拖垮整轮扫描
            failedJars.put(key, Long.valueOf(modified));
            TechnicalLog.event("plugin.load_failed", 0L, "jar=" + jar.getFileName(),
                    "type=" + failure.getClass().getName(), "msg=" + failure.getMessage());
        }
    }

    private List<Path> pluginDirectories() {
        List<Path> out = new ArrayList<Path>();
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsDir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    out.add(entry);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("扫描插件目录失败：" + pluginsDir, e);
        }
        return out;
    }

    private static List<Path> jarsIn(Path pluginDir) {
        List<Path> out = new ArrayList<Path>();
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(pluginDir, "*.jar")) {
            for (Path jar : stream) {
                out.add(jar);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("扫描插件目录失败：" + pluginDir, e);
        }
        return out;
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private void forget(String pluginId) {
        PluginWrapper wrapper = manager.getPlugin(pluginId);
        if (wrapper != null && wrapper.getPluginPath() != null) {
            String key = wrapper.getPluginPath().toAbsolutePath().toString();
            loadedJars.remove(key);
            failedJars.remove(key);
        }
    }

    @Override
    public void unload(PluginId pluginId) {
        manager.unloadPlugin(pluginId.getValue());
        forget(pluginId.getValue());
    }

    @Override
    public List<String> conflictsOf(PluginId pluginId) {
        sync();
        PluginWrapper wrapper = manager.getPlugin(pluginId.getValue());
        List<String> conflicts = new ArrayList<String>();
        if (wrapper == null) {
            return conflicts;
        }
        for (String entry : entriesOf(wrapper.getPluginPath())) {
            for (String hostPackage : HOST_PACKAGES) {
                if (entry.startsWith(hostPackage) && !conflicts.contains(hostPackage)) {
                    conflicts.add(hostPackage);
                }
            }
        }
        return conflicts;
    }

    private static List<String> entriesOf(Path path) {
        List<String> out = new ArrayList<String>();
        try {
            if (Files.isDirectory(path)) {
                try (java.util.stream.Stream<Path> walked = Files.walk(path)) {
                    for (Path entry : (Iterable<Path>) walked::iterator) {
                        out.add(path.relativize(entry).toString().replace('\\', '/') + "/");
                    }
                }
            } else {
                try (ZipFile zip = new ZipFile(path.toFile())) {
                    java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        out.add(entries.nextElement().getName());
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取插件内容失败：" + path, e);
        }
        return out;
    }

    @Override
    public Object extension(PluginId pluginId, String capability) {
        sync();
        Class<?> type = capabilityTypes.get(capability);
        PluginWrapper wrapper = manager.getPlugin(pluginId.getValue());
        if (type == null || wrapper == null) {
            return null;
        }
        List<?> extensions = manager.getExtensions(type, pluginId.getValue());
        return extensions.isEmpty() ? null : extensions.get(0);
    }

    private static int apiVersionOf(PluginWrapper wrapper) {
        String requires = wrapper.getDescriptor().getRequires();
        if (requires == null || requires.trim().isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(requires.trim());
        } catch (NumberFormatException notNumeric) {
            return 0;   // 非数字的 requires 交给安装校验拒绝，不在这里抛
        }
    }

    /** `source.type` 一律 PLUGIN；端点写成 `<pluginId>/<handlerId>`（05-action §5.6）。 */
    private ToolDefinition toDomain(PluginId pluginId, String json) {
        ToolJson dto = codec.read(json, ToolJson.class);
        ToolSource source = new ToolSource(SourceType.PLUGIN,
                pluginId.getValue() + "/" + dto.source.endpointOrIdentifier);
        return new ToolDefinition(ToolId.of(dto.id), dto.name, dto.description,
                new ToolSchema(text(dto.inputSchema)), new ToolSchema(text(dto.outputSchema)), source,
                SideEffectLevel.valueOf(dto.sideEffect), dto.timeoutMillis, ToolStatus.valueOf(dto.status));
    }

    private static byte[] read(ZipFile zip, ZipEntry entry) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        try (java.io.InputStream in = zip.getInputStream(entry)) {
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        return out.toByteArray();
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取工具声明失败：" + file, e);
        }
    }

    private static String text(JsonNode node) {
        return node == null ? "" : node.toString();
    }

    /**
     * 关掉 PF4J 自带的状态文件：否则它会另存一份启停状态，与 `plugin.json` 形成两份真相。
     */
    private static final class JellyfishPluginManager extends DefaultPluginManager {

        private static final PluginStatusProvider NO_STATE = new PluginStatusProvider() {
            @Override
            public boolean isPluginDisabled(String pluginId) {
                return false;
            }

            @Override
            public void disablePlugin(String pluginId) {
            }

            @Override
            public void enablePlugin(String pluginId) {
            }
        };

        JellyfishPluginManager(Path pluginsDir) {
            super(pluginsDir);
        }

        @Override
        protected PluginStatusProvider createPluginStatusProvider() {
            return NO_STATE;
        }
    }

    /** 插件 jar 内 `tools/*.json` 的存储形态（与 `tools/*.json` 同结构）。 */
    static class ToolJson {

        public String id;
        public String name;
        public String description;
        public JsonNode inputSchema;
        public JsonNode outputSchema;
        public SourceJson source = new SourceJson();
        public String sideEffect;
        public int timeoutMillis;
        public String status;
    }

    static class SourceJson {

        public String endpointOrIdentifier;
    }
}
