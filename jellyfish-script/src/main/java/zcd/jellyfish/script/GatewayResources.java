package zcd.jellyfish.script;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 网关资源的落盘：把桥接插件 jar 里的网关脚本抽到文件系统上，交回一个可以直接执行的目录。
 * <p>
 * <b>为什么必须落盘</b>：解释器只能执行文件系统上的文件，读不了 jar 条目。
 * 「把代码塞进 {@code -c} 参数」看起来能避开这一步，但会让命令行里出现一段多行源码
 * （进程列表里可见、转义脆弱、无法单独查看），排查问题时反而更难。
 * <p>
 * <b>目录名带内容摘要</b>：抽取结果按「语言 + 全部资源内容」的摘要分目录，
 * 于是「资源变了」自动得到一个新目录，绝不会出现「上一版网关文件还在被复用」——
 * 那类问题在开发期表现为「改了网关代码却没生效」，而人都倾向于先去怀疑缓存的另一边。
 * 旧目录不清理：它们很小，留着才能在事后确认「当时到底跑的哪一份」。
 * <p>
 * <b>写入先落临时目录再改名</b>：目录一旦出现就代表内容完整。
 * 否则并发启动两个 JVM 时，另一个进程可能读到只写了一半的网关文件，
 * 报出来的是「网关语法错误」，与真正原因（两个进程同时抽取）相隔很远。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class GatewayResources {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(GatewayResources.class);

    /** 摘要目录名长度：够避免碰撞，又不至于让路径难以阅读。 */
    private static final int DIGEST_LENGTH = 16;

    /** 抽取根目录。 */
    private final Path baseDirectory;

    /** 字节来源；生产固定为「语言适配的类加载器」，测试可注入固定内容。 */
    private final ResourceLoader loader;

    /**
     * 构造资源抽取器。
     *
     * @param baseDirectory 抽取根目录（其下按语言与摘要分目录），不可为 {@code null}
     */
    public GatewayResources(Path baseDirectory) {
        this(baseDirectory, GatewayResources::readFromClasspath);
    }

    /**
     * 构造资源抽取器，指定字节来源。
     *
     * @param baseDirectory 抽取根目录，不可为 {@code null}
     * @param loader        字节来源，不可为 {@code null}
     */
    GatewayResources(Path baseDirectory, ResourceLoader loader) {
        this.baseDirectory = baseDirectory;
        this.loader = loader;
    }

    /**
     * 计算默认抽取根目录（{@code ~/.jellyfish/gateway}）。
     * <p>
     * 选用户主目录而不是临时目录：临时目录会被系统或用户清理，
     * 清理掉正在运行的网关文件在 POSIX 上无害（文件已打开）但在别的平台上会变成「脚本中途消失」；
     * 也不选进程工作目录，否则每个项目都会各存一份完全相同的网关。
     *
     * @return 默认抽取根目录
     */
    public static Path defaultBaseDirectory() {
        return Paths.get(System.getProperty("user.home"), ".jellyfish", "gateway");
    }

    /**
     * 把某个语言的网关资源抽取到文件系统。
     * <p>
     * 已经抽取过（目录与全部文件都在）时直接复用，不重写文件：重复写会让文件的修改时间不断变化，
     * 而「文件没变过」是排查「我改了代码为什么没生效」时唯一能确认的事实。
     *
     * @param language        语言适配，不可为 {@code null}；用于分目录与取类加载器
     * @param resourceNames   需要落盘的类路径资源名，不可为 {@code null}
     * @return 网关资源所在目录的绝对路径，保证非 {@code null}
     * @throws JellyfishException 资源缺失或写入失败时抛出
     */
    public Path materialize(ScriptLanguage language, List<String> resourceNames) {
        List<String> names = new ArrayList<String>(resourceNames);
        if (names.isEmpty()) {
            throw new JellyfishException("语言 " + language.id() + " 没有声明任何网关资源");
        }
        List<Resource> resources = new ArrayList<Resource>();
        for (String name : names) {
            resources.add(new Resource(name, loader.load(language, name)));
        }
        Path directory = baseDirectory.resolve(language.id()).resolve(digestOf(resources));
        if (isComplete(directory, resources)) {
            LOG.debug("复用已抽取的网关资源: {}", directory);
            return directory;
        }
        write(directory, resources);
        LOG.info("已抽取 {} 网关资源到 {}", language.id(), directory);
        return directory;
    }

    /**
     * 从语言适配的类加载器读取一个网关资源。
     *
     * @param language 语言适配
     * @param name     资源名
     * @return 资源内容
     * @throws JellyfishException 资源不存在时抛出
     */
    private static byte[] readFromClasspath(ScriptLanguage language, String name) {
        // 用语言适配自己的类加载器：资源在桥接插件 jar 里，只有它的加载器能看到；
        // 用本类的加载器会在「插件由 PF4J 子优先加载」时找不到——那正是生产环境的形态
        ClassLoader loader = language.getClass().getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(name)) {
            if (stream == null) {
                throw new JellyfishException("网关资源不存在: " + name + "（语言 " + language.id() + "）");
            }
            return readFully(stream);
        } catch (IOException e) {
            throw new JellyfishException("读取网关资源失败: " + name, e);
        }
    }

    /**
     * 完整读取输入流。
     *
     * @param stream 输入流
     * @return 字节内容
     * @throws IOException 读取失败时抛出
     */
    private static byte[] readFully(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /**
     * 判断目录里的资源是否与当前内容一致。
     *
     * @param directory 目标目录
     * @param resources 资源清单
     * @return 目录存在且全部资源都在时返回 {@code true}
     */
    private boolean isComplete(Path directory, List<Resource> resources) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        for (Resource resource : resources) {
            Path file = directory.resolve(resource.relativePath());
            if (!Files.isRegularFile(file)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 写入资源。
     * <p>
     * 先写临时目录再整体改名：目录名存在即内容完整，读方不必加锁也不会看到半成品。
     *
     * @param directory 目标目录
     * @param resources 资源清单
     * @throws JellyfishException 写入失败时抛出
     */
    private void write(Path directory, List<Resource> resources) {
        Path staging = null;
        try {
            Files.createDirectories(directory.getParent());
            staging = Files.createTempDirectory(directory.getParent(), "staging-");
            for (Resource resource : resources) {
                Path target = staging.resolve(resource.relativePath());
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (OutputStream output = Files.newOutputStream(target)) {
                    output.write(resource.content());
                }
            }
            if (isComplete(directory, resources)) {
                // 另一个进程刚好抢先写完：复用它的结果，别把已完成目录覆盖掉
                delete(staging);
                return;
            }
            moveIntoPlace(staging, directory, resources);
        } catch (IOException e) {
            throw new JellyfishException("写入网关资源失败: " + directory, e);
        } finally {
            if (staging != null) {
                delete(staging);
            }
        }
    }

    /**
     * 把临时目录改名到目标位置。
     * <p>
     * 目标已存在（并发赢家）时直接丢掉本次结果：内容由摘要决定，因此两者必然等价。
     *
     * @param staging   临时目录
     * @param directory 目标目录
     * @param resources 资源清单
     * @throws IOException 改名失败且目标不可用时抛出
     */
    private void moveIntoPlace(Path staging, Path directory, List<Resource> resources) throws IOException {
        try {
            Files.move(staging, directory, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException | AtomicMoveNotSupportedException e) {
            if (isComplete(directory, resources)) {
                LOG.debug("网关资源目录已由其它进程创建，复用之: {}", directory);
                return;
            }
            // 原子改名不受支持时退化为「按文件逐个搬」：目录名既然已存在就说明内容不全，
            // 此时补写缺的文件比报错更合适
            LOG.debug("网关资源目录改名退化处理: {}", e.toString());
            Files.createDirectories(directory);
            for (Resource resource : resources) {
                Path target = directory.resolve(resource.relativePath());
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.write(target, resource.content());
            }
        }
    }

    /**
     * 递归删除，失败只告警。
     *
     * @param directory 待删除目录，可为 {@code null}
     */
    private static void delete(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    LOG.warn("清理临时目录项失败: {} ({})", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            LOG.warn("清理临时目录失败: {} ({})", directory, e.getMessage());
        }
    }

    /**
     * 计算资源集合的内容摘要。
     *
     * @param resources 资源清单
     * @return 十六进制摘要（截短）
     */
    private static String digestOf(List<Resource> resources) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Resource resource : resources) {
                digest.update(resource.relativePath().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(resource.content());
                digest.update((byte) 0);
            }
            String hex = new BigInteger(1, digest.digest()).toString(16);
            StringBuilder padded = new StringBuilder(hex);
            while (padded.length() < DIGEST_LENGTH) {
                padded.insert(0, '0');
            }
            return padded.substring(0, DIGEST_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new JellyfishException("计算网关资源摘要失败", e);
        }
    }

    /**
     * 网关资源的字节来源。
     * <p>
     * <b>为什么要有这一层</b>：生产环境里资源只可能来自「桥接插件类的加载器」，
     * 但那样一来「内容变了就换目录」这条最要紧的性质就无法被验证——测试没法在不改 jar 的前提下
     * 让同一个资源名返回不同内容。把「字节从哪来」变成可替换的一步，这条性质才有测试守着。
     */
    @FunctionalInterface
    interface ResourceLoader {

        /**
         * 读取资源内容。
         *
         * @param language 语言适配
         * @param name     资源名
         * @return 资源字节
         * @throws JellyfishException 资源不存在时抛出
         */
        byte[] load(ScriptLanguage language, String name);
    }

    /**
     * 一个待落盘的网关资源。
     * <p>
     * 保留相对路径是为了支持「网关 + 该语言的运行时辅助模块」这种多文件形态：
     * 语言适配声明的资源名可能是包路径（{@code pkg/gateway.py}），
     * 落盘后要保持同样的相对结构，解释器的 import 才找得到。
     *
     * @param resourceName 资源名（类路径上的名字）
     * @param content      资源内容
     * @author zcd
     */
    private static final class Resource {

        /** 资源名。 */
        private final String resourceName;

        /** 资源内容。 */
        private final byte[] content;

        /**
         * 构造资源。
         *
         * @param resourceName 资源名
         * @param content      资源内容
         */
        private Resource(String resourceName, byte[] content) {
            this.resourceName = resourceName;
            this.content = content;
        }

        /**
         * 获取资源内容。
         *
         * @return 资源字节
         */
        private byte[] content() {
            return content;
        }

        /**
         * 获取落盘后的相对路径。
         * <p>
         * 去掉前导斜杠：从类路径根取到的资源名可能带它，而 {@code Path.resolve} 遇到绝对路径
         * 会忽略前面的目录，把文件写到抽取根目录之外。
         *
         * @return 相对路径
         */
        private String relativePath() {
            String name = resourceName;
            int index = 0;
            while (index < name.length() && name.charAt(index) == '/') {
                index++;
            }
            return index == 0 ? name : name.substring(index);
        }
    }
}
