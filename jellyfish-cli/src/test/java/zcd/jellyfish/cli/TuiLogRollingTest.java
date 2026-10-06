package zcd.jellyfish.cli;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.ConfigurationFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TUI 日志配置（{@code log4j2-tui.xml}）的滚动验证：真起一个 Log4j2 上下文、真写日志、
 * 真检查盘上留下了什么。
 * <p>
 * <b>为什么它必须写真的日志</b>：这份配置的整个要点是「文件不会无限增长」，而这句话只有落到
 * 「磁盘上到底有几个文件、一共多少字节」才可证。断言 XML 里有 {@code RollingFile} 三个字证明不了
 * 任何事——属性名写错、单位写错、上限写成 0，XML 都照样能解析通过，而故障要等到用户几天后
 * 发现磁盘满了才会暴露。
 * <p>
 * <b>为什么用独立的 {@link LoggerContext} 而不是全局那个</b>：本仓库的其它单测也会记日志，
 * 而 Log4j2 的配置在第一个 Logger 被创建时就定死了。若去改全局上下文（{@code Configurator} 或
 * {@code log4j.configurationFile}），本用例的结果就取决于「谁先跑」——而且它会把后面所有用例的
 * 日志重定向到临时文件里。自己造一个上下文，则既不依赖顺序、也不影响别人。
 * <p>
 * <b>为什么 {@code @AfterEach} 要清系统属性</b>：配置里的 {@code ${sys:...}} 读的是进程级属性，
 * 用完不清就会漏给同 JVM 里别的用例——这个坑在本用例的第一次实跑里就出现过：
 * 「默认预算」那条读到了前一条用例设的 10 KB，于是它验证的其实是覆盖值而不是缺省值。
 *
 * @author zcd
 */
@DisplayName("TUI 日志滚动")
class TuiLogRollingTest {

    /** 日志目标文件路径的系统属性名，与配置里读取的一致。 */
    private static final String FILE_PROPERTY = "jellyfish.log.file";

    /** 单文件上限的系统属性名。 */
    private static final String MAX_SIZE_PROPERTY = "jellyfish.log.maxSize";

    /** 保留档数的系统属性名。 */
    private static final String MAX_FILES_PROPERTY = "jellyfish.log.maxFiles";

    /** 配置文件在类路径上的名字，与 {@code JellyfishApplication} 用的是同一份。 */
    private static final String CONFIG_RESOURCE = "/log4j2-tui.xml";

    /** 缺省单文件上限（10 MB）：配置里的字面量，用于断言缺省值真的被解析成了这个量级。 */
    private static final long DEFAULT_MAX_SIZE_BYTES = 10L * 1024L * 1024L;

    /** 相对上限的容差：Log4j2 在下一次写之前不检查大小，因此落盘的那一份会略微超上限。 */
    private static final long SIZE_TOLERANCE = DEFAULT_MAX_SIZE_BYTES / 10L;

    /**
     * 清掉本用例设过的系统属性，避免漏给同 JVM 里的其它用例。
     */
    @AfterEach
    void tearDown() {
        System.clearProperty(FILE_PROPERTY);
        System.clearProperty(MAX_SIZE_PROPERTY);
        System.clearProperty(MAX_FILES_PROPERTY);
    }

    @Test
    @DisplayName("超过单文件上限时滚动，且历史档数不超过保留上限")
    void tuiLog_should_roll_and_cap_files_when_sizeExceeded(@TempDir Path directory) throws Exception {
        Path logFile = directory.resolve("jellyfish-tui.log");
        System.setProperty(FILE_PROPERTY, logFile.toString());
        System.setProperty(MAX_SIZE_PROPERTY, "10 KB");
        System.setProperty(MAX_FILES_PROPERTY, "2");

        writeLog(directory, logFile, 3000, "滚动验证：写进来的字节数远超磁盘上留下的字节数");

        List<Path> files = listLogFiles(directory);
        // 当前文件 + 2 个历史档 = 3；出现第 4 个文件就说明淘汰没生效
        assertEquals(3, files.size(), "应当正好保留当前文件与 2 个历史档：" + names(files));
        assertTrue(Files.exists(logFile), "当前日志必须仍然写在配置指定的那个文件名上");
        assertTrue(olderThanCurrent(files, logFile, 11L * 1024L), "每个历史档都不得超过单文件上限");
        // 关键断言：写入约 150 KB，盘上只留约 25 KB —— 这就是「不再无限增长」
        assertTrue(totalBytes(files) < 30L * 1024L, "落盘总量必须有上限：" + totalBytes(files));
    }

    @Test
    @DisplayName("不配任何属性时按缺省预算滚动：单文件上限是 10 MB，不是几字节")
    void tuiLog_should_use_defaultBudget_byDefault(@TempDir Path directory) throws Exception {
        Path logFile = directory.resolve("jellyfish-tui.log");
        System.setProperty(FILE_PROPERTY, logFile.toString());

        // 写入略多于 10 MB：缺省上限若被解析错（例如当成 10 字节），这里会滚出成百上千个文件
        writeLog(directory, logFile, 3200, bigLine(), DEFAULT_MAX_SIZE_BYTES + 2L * 1024L * 1024L);

        List<Path> files = listLogFiles(directory);
        assertEquals(2, files.size(), "写入 12 MB 时应当正好滚出 1 个历史档：" + names(files));
        long archived = fileSize(logFile.getParent().resolve("jellyfish-tui.log.1"));
        assertTrue(archived >= DEFAULT_MAX_SIZE_BYTES - SIZE_TOLERANCE,
                "历史档应当接近缺省上限，实际 " + archived);
    }

    /**
     * 起一个独立的 Log4j2 上下文，按真实配置写日志，然后关掉它（确保缓冲落到盘上）。
     *
     * @param directory 配置所在目录（此处只用于定位日志文件，无实际读取）
     * @param logFile   期望的日志文件路径，用于断言
     * @param lines     要写的行数
     * @param padding   每条消息的填充内容，用于控制写入总量
     * @throws Exception 配置读取或写入失败时抛出
     */
    private static void writeLog(Path directory, Path logFile, int lines, String padding) throws Exception {
        writeLog(directory, logFile, lines, padding, 0L);
    }

    /**
     * 起一个独立的 Log4j2 上下文，按真实配置写日志，直到写入量达到给定规模。
     *
     * @param directory  配置所在目录（此处只用于定位日志文件，无实际读取）
     * @param logFile    期望的日志文件路径，用于断言
     * @param maxLines   最多写多少行（避免死循环）
     * @param padding    每条消息的填充内容，用于控制写入总量
     * @param minBytes   至少要写够多少字节；为 0 时按行数写满即可
     * @throws Exception 配置读取或写入失败时抛出
     */
    private static void writeLog(Path directory, Path logFile, int maxLines, String padding, long minBytes)
            throws Exception {
        URI uri = TuiLogRollingTest.class.getResource(CONFIG_RESOURCE).toURI();
        // 上下文名带上目标文件，避免 Log4j2 按名字缓存住上一轮的配置
        LoggerContext context = new LoggerContext("tui-log-rolling-" + logFile.getFileName());
        try {
            Configuration configuration = ConfigurationFactory.getInstance()
                    .getConfiguration(context, "tui", uri, TuiLogRollingTest.class.getClassLoader());
            context.start(configuration);
            // 触发日志文件路径解析：fileName 是 ${sys:jellyfish.log.file} 的代换结果，读取时才会落到盘上
            logFile.toFile().getParentFile().mkdirs();
            Logger logger = context.getLogger("tui-log-probe");
            for (int i = 0; i < maxLines; i++) {
                logger.warn("填充第 {} 行：{}", i, padding);
                if (minBytes > 0L && Files.exists(logFile) && writtenBytes(logFile) >= minBytes) {
                    break;
                }
            }
        } finally {
            context.stop();
        }
    }

    /**
     * 估算「这次写入已经落盘多少字节」：当前文件加上已经滚出去的历史档。
     *
     * @param logFile 当前日志文件
     * @return 字节数
     * @throws IOException 读取失败时抛出
     */
    private static long writtenBytes(Path logFile) throws IOException {
        return totalBytes(listLogFiles(logFile.getParent()));
    }

    /**
     * 一条足够长的填充消息：用较少的行数写满 10 MB，避免几十万次格式化拖慢用例。
     *
     * @return 填充文本，保证非 {@code null}
     */
    private static String bigLine() {
        StringBuilder padding = new StringBuilder(4096);
        for (int i = 0; i < 256; i++) {
            padding.append("0123456789abcdef");
        }
        return padding.toString();
    }

    /**
     * 列出目录下的日志文件（含滚动档）。
     *
     * @param directory 目录，不可为 {@code null}
     * @return 文件列表，保证非 {@code null}
     * @throws IOException 列目录失败时抛出
     */
    private static List<Path> listLogFiles(Path directory) throws IOException {
        List<Path> files = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "jellyfish-tui.log*")) {
            for (Path path : stream) {
                files.add(path);
            }
        }
        return files;
    }

    /**
     * 判断所有历史档是否都不超过给定大小。
     *
     * @param files          目录下的全部日志文件
     * @param logFile        当前日志文件（不参与判断）
     * @param maxBytes       单档上限
     * @return 全部不超限返回 {@code true}
     * @throws IOException 读取失败时抛出
     */
    private static boolean olderThanCurrent(List<Path> files, Path logFile, long maxBytes) throws IOException {
        for (Path path : files) {
            if (!path.equals(logFile) && Files.size(path) > maxBytes) {
                return false;
            }
        }
        return true;
    }

    /**
     * 求一组文件的字节数合计。
     *
     * @param files 文件列表
     * @return 字节数
     * @throws IOException 读取失败时抛出
     */
    private static long totalBytes(List<Path> files) throws IOException {
        long total = 0L;
        for (Path path : files) {
            total += Files.size(path);
        }
        return total;
    }

    /**
     * 取单个文件的大小；文件不存在时为 0。
     *
     * @param path 路径
     * @return 字节数
     * @throws IOException 读取失败时抛出
     */
    private static long fileSize(Path path) throws IOException {
        return Files.exists(path) ? Files.size(path) : 0L;
    }

    /**
     * 把文件列表转成便于读失败消息的名字列表。
     *
     * @param files 文件列表
     * @return 名字列表
     */
    private static List<String> names(List<Path> files) {
        List<String> result = new ArrayList<String>(files.size());
        for (Path path : files) {
            result.add(path.getFileName().toString());
        }
        return result;
    }
}
