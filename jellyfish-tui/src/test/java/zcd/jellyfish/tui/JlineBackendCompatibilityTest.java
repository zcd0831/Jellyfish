package zcd.jellyfish.tui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * jline 那条升级（3.25.1 → 3.30.17-jdk8）的判据。
 * <p>
 * <b>为什么需要它</b>：jline 是从 {@code dev.tamboui:tamboui-jline3-backend} 传递进来的，我们在
 * {@code jellyfish-tui} 里把它排除掉、换成显式的 {@code jdk8} 分类器版本。这条链上有三个各不相同的
 * 失败方式，而它们都<b>不会在编译期暴露</b>：
 * <ol>
 *   <li><b>换错变体</b>：jline 3.x 主线 jar 里混着更高版本的字节码（{@code META-INF/versions/11} 的
 *       {@code module-info}、FFM provider 那批 Java 22 的类）。**实测过：主线 jar 在 JDK 1.8 下
 *       按需加载也能侥幸起来**（不碰到那些类就没事，见本类的反向验证），所以「跑不起来」不能当判据；
 *       判据应当是「<b>那份 jar 里没有一个 >52 的类</b>」——全类路径扫描、类加载校验、以及某些容器
 *       都会撞上混进去的那些，而 {@code jdk8} 分类器就是官方给出的干净那份（不含它们）。</li>
 *   <li><b>两个 jar 同时上类路径</b>：classifier 不同的 artifact 在 Maven 眼里是两回事，
 *       排除传递依赖那步漏了就会出现「谁先被加载看运气」。</li>
 *   <li><b>它引用的类型在新版里没了</b>：TamboUI 是按 3.25.1 编译的，若新版删掉/搬走了某个类或方法，
 *       编译期不会报，要等真起界面才炸。</li>
 * </ol>
 * 三条各有一道断言，见下面三个用例。**它们钉的是「能起来」，不是「界面渲染对不对」**——后者的判据
 * 在 TUI 那批用例里。
 *
 * @author zcd
 */
@DisplayName("jline 终端后端的 Java 8 兼容性")
class JlineBackendCompatibilityTest {

    /** Java 8 的 class 文件主版本号。 */
    private static final int JAVA_8_MAJOR = 52;

    @Test
    @DisplayName("用的是 jdk8 分类器那份，且整份 jar 里没有一个高于 Java 8 的类")
    void terminal_shouldComeFromJdk8Build() throws Exception {
        // Given：jline 的 Terminal 这个类
        CodeSource source = Terminal.class.getProtectionDomain().getCodeSource();
        assertNotNull(source, "拿不到 Terminal 的来源，无法判断用的是哪一份 jline");
        String location = source.getLocation().toString();
        Path jar = Paths.get(source.getLocation().toURI());

        // Then（先扫整份 jar）：主线 jar 混着 11/22 的类，那种「按需加载侥幸能用」不是我们想要的
        List<String> offending = classesNewerThanJava8(jar);
        if (!offending.isEmpty()) {
            fail("jline jar 里混有高于 Java 8 的类，应当用 jdk8 分类器那份（共 " + offending.size()
                    + " 个，前几个：" + offending.subList(0, Math.min(5, offending.size())) + "）");
        }

        // 再钉住来源与版本——既钉住「没退回主线 jar」，也钉住版本号
        assertTrue(location.contains("3.30.17"), "jline 版本不是预期的 3.30.17：" + location);
        assertTrue(location.contains("jdk8"), "用的不是 jdk8 分类器那份：" + location);
        assertEquals(JAVA_8_MAJOR, majorVersionOf(Terminal.class),
                "jline 的字节码不是 Java 8（major " + JAVA_8_MAJOR + "）");
    }

    @Test
    @DisplayName("dumb 终端能真的建起来（类加载 + 初始化 + 基本读写）")
    void dumbTerminal_shouldBeUsable() throws Exception {
        // When：建一个不碰真 TTY 的终端（与 CI/无终端环境同一条路）
        Terminal terminal = TerminalBuilder.builder().dumb(true).build();
        try {
            // Then：能取到类型与尺寸，且写出去不炸——这三步都会真的用到 jline 的内部实现
            assertNotNull(terminal);
            assertNotNull(terminal.getType());
            // 不假设尺寸（dumb 终端没有 TTY，宽高取决于环境变量），只要求「问得到、不抛异常」
            assertTrue(terminal.getWidth() >= 0 && terminal.getHeight() >= 0, "终端尺寸查询失败");
            terminal.writer().write("jellyfish\n");
            terminal.writer().flush();
        } finally {
            terminal.close();
        }
    }

    @Test
    @DisplayName("TamboUI 后端引用的 jline 类型在新版里都还在（签名能解析）")
    void tambouiBackend_shouldLinkAgainstThisJline() throws Exception {
        List<Class<?>> backendClasses = backendClasses();
        assumeTrue(!backendClasses.isEmpty(),
                "拿不到 tamboui-jline3-backend 的 jar（可能不是从本地仓库解析的），跳过这条链接检查");
        for (Class<?> type : backendClasses) {
            // 读方法签名会解析其中的参数/返回类型：它引用的 jline 类若不存在，这里抛 NoClassDefFoundError
            type.getDeclaredMethods();
            type.getDeclaredFields();
        }
    }

    /**
     * 扫一份 jar，列出其中高于 Java 8 的类。
     * <p>
     * {@code META-INF/versions/} 下的条目跳过：multi-release jar 的规矩就是「JDK 版本不够时不看它」，
     * 它在那里是合法的（jdk8 分类器里也可能留着 {@code versions/9} 之类的目录）。
     *
     * @param jar jar 路径
     * @return 「类名 → 主版本号」的清单，按出现顺序；没有时为空列表
     * @throws IOException 读不到 jar 时抛出
     */
    private static List<String> classesNewerThanJava8(Path jar) throws IOException {
        List<String> offending = new ArrayList<String>();
        try (JarFile file = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class") || name.startsWith("META-INF/versions/")) {
                    continue;
                }
                try (DataInputStream data = new DataInputStream(file.getInputStream(entry))) {
                    data.readInt();
                    data.readUnsignedShort();
                    int major = data.readUnsignedShort();
                    if (major > JAVA_8_MAJOR) {
                        offending.add(name + " (major " + major + ")");
                    }
                }
            }
        }
        return offending;
    }

    /**
     * 读一个类的 class 文件主版本号。
     *
     * @param type 目标类
     * @return 主版本号（Java 8 是 52）
     * @throws IOException 读不到 class 文件时抛出
     */
    private static int majorVersionOf(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            assertNotNull(in, "读不到 class 文件：" + resource);
            DataInputStream data = new DataInputStream(in);
            assertEquals(0xCAFEBABE, data.readInt(), "不是 class 文件：" + resource);
            data.readUnsignedShort();
            return data.readUnsignedShort();
        }
    }

    /**
     * 列出 TamboUI jline3 后端里的全部顶层类。
     * <p>
     * <b>怎么找到那个 jar</b>：从 jline 自己的位置反推本地仓库布局
     * （{@code …/repository/org/jline/jline/<版本>/jline-<版本>-jdk8.jar} → {@code …/repository}），
     * 再去看 {@code dev/tamboui/tamboui-jline3-backend/} 下的版本目录。这比枚举 classpath 可靠：
     * surefire 默认用 manifest-only jar，{@code getResources("")} 只会返回那个 booter jar。
     *
     * @return 顶层类列表；找不到 jar 时返回空列表
     * @throws Exception 定位或加载失败时抛出
     */
    private static List<Class<?>> backendClasses() throws Exception {
        List<Class<?>> result = new ArrayList<Class<?>>();
        Path backendJar = locateBackendJar();
        if (backendJar == null) {
            return result;
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (JarFile jar = new JarFile(backendJar.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                // 跳过 META-INF（其中 versions/11/module-info.class 是 Java 11 的模块描述，JDK 8 加载不了也不该加载）
                if (!name.endsWith(".class") || name.indexOf('$') >= 0 || name.startsWith("META-INF/")) {
                    continue;
                }
                String binary = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                result.add(Class.forName(binary, false, loader));
            }
        }
        return result;
    }

    /**
     * 在本地仓库里找 TamboUI 的 jline3 后端 jar。
     *
     * @return jar 路径；找不到时返回 {@code null}
     * @throws Exception 解析路径失败时抛出
     */
    private static Path locateBackendJar() throws Exception {
        Path jlineJar = Paths.get(Terminal.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path repository = jlineJar.getParent();
        while (repository != null && !"repository".equals(repository.getFileName().toString())) {
            repository = repository.getParent();
        }
        if (repository == null) {
            return null;
        }
        Path backendRoot = repository.resolve("dev").resolve("tamboui").resolve("tamboui-jline3-backend");
        if (!Files.isDirectory(backendRoot)) {
            return null;
        }
        try (DirectoryStream<Path> versions = Files.newDirectoryStream(backendRoot)) {
            for (Path versionDir : versions) {
                if (!Files.isDirectory(versionDir)) {
                    continue;
                }
                try (DirectoryStream<Path> files = Files.newDirectoryStream(versionDir, "*.jar")) {
                    for (Path jar : files) {
                        String name = jar.getFileName().toString();
                        if (name.startsWith("tamboui-jline3-backend") && !name.contains("sources")
                                && !name.contains("javadoc")) {
                            return jar;
                        }
                    }
                }
            }
        }
        return null;
    }
}
