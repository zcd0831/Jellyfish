package zcd.jellyfish.infra.tooloutput;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.config.ToolOutputSettings;
import zcd.jellyfish.infra.support.HomePaths;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 工具结果落盘：把被截断的完整内容写到磁盘，供模型按需回查。
 * <p>
 * <b>为什么写在用户主目录</b>（缺省 {@code ~/.jellyfish/tool-outputs}）：它是运行产物而非项目内容。
 * 写进项目工作区会污染版本控制，也会让「工具跑过一次」变成一次源码树改动。
 * <p>
 * <b>写失败不阻断回合</b>：本类只返回 {@code null} 并记一条 WARN，由调用点如实告诉模型
 * 「落盘失败、内容不可恢复」。磁盘不可写是环境问题，把它升级成「工具调用失败」只会让用户
 * 更难定位——他要的是这次对话能继续，而不是一个更响的报错。
 * <p>
 * <b>先写临时文件再原子改名</b>：与 PID 文件同一口径。一条只写了一半的 JSON 被模型读到，
 * 比「文件不存在」更糟——它会被当成真的。
 * <p>
 * <b>清理是尽力而为</b>：按文件数 / 总字节两个上限从最旧开始删，任何一次删除失败都只记 WARN。
 * 清理失败的正确后果是「多占一点磁盘」，不是「这一轮对话发不出去」。
 *
 * @author zcd
 */
@Singleton
public class ToolOutputStore {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ToolOutputStore.class);

    /** 文件名里允许保留的字符之外一律替换成它。 */
    private static final String REPLACEMENT = "_";

    /** 单个路径片段的长度上限，避免非法超长文件名。 */
    private static final int MAX_SEGMENT_LENGTH = 120;

    /** 结构化结果的扩展名。 */
    private static final String JSON_SUFFIX = ".json";

    /** 纯文本结果的扩展名。 */
    private static final String TEXT_SUFFIX = ".txt";

    /** 运行时配置门面：落盘目录与两个清理阈值现读。 */
    private final RuntimeConfig runtimeConfig;

    /**
     * 构造落盘存储。
     *
     * @param runtimeConfig 运行时配置门面，不可为 {@code null}
     */
    @Inject
    public ToolOutputStore(RuntimeConfig runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
    }

    /**
     * 把一次工具结果的完整内容落盘。
     *
     * @param sessionId  会话标识，可为 {@code null}（归到 {@code unknown-session}）
     * @param toolCallId 工具调用标识，可为 {@code null}
     * @param toolName   工具名，可为 {@code null}
     * @param content    完整内容，不可为 {@code null}
     * @param structured 是否为结构化结果（决定扩展名为 {@code .json} 还是 {@code .txt}）
     * @return 落盘文件的绝对路径；失败时返回 {@code null}
     */
    public String store(String sessionId, String toolCallId, String toolName, String content, boolean structured) {
        if (content == null) {
            return null;
        }
        ToolOutputSettings settings = runtimeConfig.getReactSettings().getToolOutput();
        try {
            Path directory = sessionDirectory(settings, sessionId);
            Files.createDirectories(directory);
            Path target = directory.resolve(fileName(toolCallId, toolName, structured));
            writeAtomically(directory, target, content);
            cleanup(directory, target, settings);
            return target.toAbsolutePath().toString();
        } catch (IOException | RuntimeException e) {
            LOG.warn("工具结果落盘失败: sessionId={} tool={} reason={}", sessionId, toolName, e.toString());
            return null;
        }
    }

    /**
     * 把一份内容落到指定命名空间（根目录下的一个子目录）。
     * <p>
     * <b>为什么需要命名空间而不是复用会话目录</b>：有些产物根本不是「某会话的一次工具调用」——
     * 例如子代理的 run 归档，它的键是 {@code runId}，而一个 run 可能没有工具调用。
     * 命名空间让它们共用同一套落盘规则（原子改名、清洗、清理）却<b>互不干扰</b>：
     * 各自的目录、各自的配额，一侧的清理不会把另一侧的窗口掏空。
     * <p>
     * <b>配额为什么是调用参数而不是配置</b>：不同产物的合理上限差很多（一份工具结果几十 KB，
     * 一份 run 归档含整份 transcript），把「谁的上限是多少」放在调用点旁边比挤进一个
     * 共享的配置段更好读——配置只是把调用点传的值搬过来（见 {@code SubAgentSettings}）。
     *
     * @param namespace  命名空间（根目录下的子目录名），不可为空白
     * @param key        文件键（不含扩展名）
     * @param content    完整内容，不可为 {@code null}
     * @param structured 是否为结构化结果（决定扩展名为 {@code .json} 还是 {@code .txt}）
     * @param keepFiles  该命名空间最多保留的文件数，{@code 0} 表示不清理
     * @param maxBytes   该命名空间最多占用的字节数，{@code 0} 表示不清理
     * @return 落盘文件的绝对路径；失败时返回 {@code null}
     */
    public String storeIn(String namespace, String key, String content, boolean structured,
                          int keepFiles, long maxBytes) {
        if (content == null) {
            return null;
        }
        ToolOutputSettings settings = runtimeConfig.getReactSettings().getToolOutput();
        try {
            Path directory = namespaceDirectory(settings, namespace);
            Files.createDirectories(directory);
            Path target = directory.resolve(sanitize(key) + (structured ? JSON_SUFFIX : TEXT_SUFFIX));
            writeAtomically(directory, target, content);
            cleanup(directory, target, keepFiles, maxBytes);
            return target.toAbsolutePath().toString();
        } catch (IOException | RuntimeException e) {
            LOG.warn("落盘失败: namespace={} key={} reason={}", namespace, key, e.toString());
            return null;
        }
    }

    /**
     * 开一个增量写入器：内容边产生边落盘，适用于「捕获期就溢出」的无界输出。
     * <p>
     * <b>与 {@link #store} 的分工</b>：{@code store} 用于事后截断（内容已经完整地在内存里），
     * 本方法用于捕获期溢出（内容可能永远不该被完整物化）。两者共用同一套目录、命名、
     * 原子改名与清理规则，因此落盘目录里不会出现两种风格的产物。
     * <p>
     * <b>打开失败不报错</b>：返回一个「已失效」的写入器，它的 {@code write} 是无操作、
     * {@code commit} 返回 {@code null}。调用方因此不需要为「磁盘不可写」写一条分支——
     * 与 {@code store} 失败返回 {@code null} 是同一个口径：磁盘问题不该把一次工具调用升级成故障。
     *
     * @param sessionId  会话标识，可为 {@code null}（归到 {@code unknown-session}）
     * @param toolCallId 工具调用标识，可为 {@code null}
     * @param toolName   工具名，可为 {@code null}
     * @return 增量写入器，保证非 {@code null}
     */
    public SpillWriter open(String sessionId, String toolCallId, String toolName) {
        ToolOutputSettings settings = runtimeConfig.getReactSettings().getToolOutput();
        try {
            Path directory = sessionDirectory(settings, sessionId);
            Path target = directory.resolve(fileName(toolCallId, toolName, false));
            Path temp = Files.createTempFile(directory, ".tmp-", ".part");
            return new SpillWriter(directory, target, temp, settings,
                    new BufferedWriter(Files.newBufferedWriter(temp, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            LOG.warn("工具结果增量落盘失败，转入不可恢复路径: sessionId={} tool={} reason={}", sessionId, toolName,
                    e.toString());
            return new SpillWriter(null, null, null, null, null);
        }
    }

    /**
     * 增量写入器：边写边落盘，收尾时原子改名。
     * <p>
     * <b>为什么单独存在</b>：命令行的输出是「无界且不可再取」的——整份物化进内存会撑爆 JVM，
     * 而先攒后写又会让「已经产生的那部分」在溢出时才落到磁盘。本类让写入从第一段就开始。
     * <p>
     * <b>为什么写入过程对外不可见</b>：本类不会把自己写了一半的临时文件暴露出去，
     * 也不参与任何互斥判断；它在 {@link #commit()} 之前对外不存在。读到一个只写了一半的文件
     * 比没有文件更糟——它会被当成真的。
     * <p>
     * <b>调用方必须显式收尾</b>：{@link #commit()} 或 {@link #abort()}，两者幂等。
     * 未收尾就丢弃实例会在缓存目录下留下一个 {@code .part} 临时文件，由后续的清理当作普通文件删除。
     * <p>
     * <b>并发前提</b>：调用方必须保证同一会话目录不会并发写入（内核当前的前提是一轮内工具串行、
     * 一会话一在途回合）。否则另一个调用的清理会把正在写的 {@code .part} 当成「最旧文件」删掉。
     *
     * @author zcd
     */
    public static final class SpillWriter {

        /** 目标目录，打开失败时为 {@code null}。 */
        private final Path directory;

        /** 最终文件路径，打开失败时为 {@code null}。 */
        private final Path target;

        /** 临时文件路径，打开失败时为 {@code null}。 */
        private final Path temp;

        /** 本次生效的清理配置，打开失败时为 {@code null}。 */
        private final ToolOutputSettings settings;

        /** 底层写入器，打开失败或收尾后为 {@code null}。 */
        private Writer writer;

        /** 是否已出现写入失败：一旦置位就不再尝试写入，并在收尾时删除临时文件。 */
        private boolean failed;

        /** 是否已完成收尾（commit 或 abort）。 */
        private boolean closed;

        /** 已写入的字节数（按 UTF-8 计），供调用方判断是否触及落盘上限。 */
        private long bytesWritten;

        /** {@link #commit()} 的结果缓存，保证幂等。 */
        private String committedPath;

        /**
         * 构造写入器。
         *
         * @param directory 目标目录，可为 {@code null}（打开失败）
         * @param target    最终文件路径，可为 {@code null}
         * @param temp      临时文件路径，可为 {@code null}
         * @param settings  清理配置，可为 {@code null}
         * @param writer    底层写入器，可为 {@code null}
         */
        private SpillWriter(Path directory, Path target, Path temp, ToolOutputSettings settings, Writer writer) {
            this.directory = directory;
            this.target = target;
            this.temp = temp;
            this.settings = settings;
            this.writer = writer;
            this.failed = writer == null;
        }

        /**
         * 追加一段文本。
         * <p>
         * <b>线程安全</b>：命令行的 stdout 与 stderr 由两条泵线程各自读取，本方法会被并发调用。
         *
         * @param text 文本片段，可为 {@code null} 或空串
         */
        public synchronized void write(String text) {
            if (failed || writer == null || text == null || text.isEmpty()) {
                return;
            }
            try {
                writer.write(text);
                bytesWritten += utf8Length(text);
            } catch (IOException | RuntimeException e) {
                failed = true;
                LOG.warn("工具结果增量写入失败，该结果转入不可恢复路径: file={} reason={}", temp, e.toString());
            }
        }

        /**
         * 获取已写入的字节数。
         *
         * @return UTF-8 字节数，保证非负
         */
        public synchronized long getBytesWritten() {
            return bytesWritten;
        }

        /**
         * 收尾：刷新、原子改名，并做一次清理。
         *
         * @return 最终文件的绝对路径；打开失败、写入失败或改名失败时为 {@code null}
         */
        public synchronized String commit() {
            if (committedPath != null) {
                return committedPath;
            }
            if (closed) {
                return null;
            }
            closed = true;
            closeQuietly();
            if (failed || temp == null || target == null) {
                deleteQuietly(temp);
                return null;
            }
            try {
                move(temp, target);
            } catch (IOException e) {
                LOG.warn("工具结果增量落盘收尾失败: file={} reason={}", target, e.toString());
                deleteQuietly(temp);
                return null;
            }
            cleanup(directory, target, settings);
            committedPath = target.toAbsolutePath().toString();
            return committedPath;
        }

        /**
         * 放弃本次写入：关闭并删除临时文件。
         * <p>
         * 与 {@link #commit()} 二选一，先调用的那个生效。
         */
        public synchronized void abort() {
            if (closed) {
                return;
            }
            closed = true;
            closeQuietly();
            deleteQuietly(temp);
        }

        /**
         * 关闭底层写入器，失败只记 WARN。
         */
        private void closeQuietly() {
            Writer current = writer;
            writer = null;
            if (current == null) {
                return;
            }
            try {
                current.close();
            } catch (IOException e) {
                failed = true;
                LOG.warn("工具结果临时文件关闭失败: file={} reason={}", temp, e.toString());
            }
        }

        /**
         * 计算文本的 UTF-8 字节数。
         *
         * @param text 文本
         * @return 字节数
         */
        private static long utf8Length(String text) {
            return text.getBytes(StandardCharsets.UTF_8).length;
        }
    }

    /**
     * 计算某会话的落盘目录，不存在时创建。
     *
     * @param settings  工具结果设置
     * @param sessionId 会话标识，可为 {@code null}
     * @return 会话目录路径
     * @throws IOException 创建目录失败时抛出
     */
    private static Path sessionDirectory(ToolOutputSettings settings, String sessionId) throws IOException {
        Path root = Paths.get(HomePaths.expand(settings.getDir()));
        Path directory = root.resolve(sanitize(sessionId == null ? "unknown-session" : sessionId));
        Files.createDirectories(directory);
        return directory;
    }

    /**
     * 计算某命名空间的目录，不存在时创建。
     *
     * @param settings  工具结果设置（只为拿根目录）
     * @param namespace 命名空间，可为 {@code null}（归到 {@code shared}）
     * @return 目录路径
     * @throws IOException 创建目录失败时抛出
     */
    private static Path namespaceDirectory(ToolOutputSettings settings, String namespace) throws IOException {
        Path root = Paths.get(HomePaths.expand(settings.getDir()));
        Path directory = root.resolve(sanitize(namespace == null ? "shared" : namespace));
        Files.createDirectories(directory);
        return directory;
    }

    /**
     * 原子写入文件：先写同目录临时文件，再改名到目标。
     *
     * @param directory 目标目录（临时文件与目标同目录，保证改名不跨文件系统）
     * @param target    目标文件
     * @param content   内容
     * @throws IOException 写入或改名失败时抛出
     */
    private static void writeAtomically(Path directory, Path target, String content) throws IOException {
        Path temp = Files.createTempFile(directory, ".tmp-", ".part");
        Files.write(temp, content.getBytes(StandardCharsets.UTF_8));
        move(temp, target);
    }

    /**
     * 把临时文件改名到目标路径，尽量保留原子性。
     *
     * @param temp   临时文件
     * @param target 目标路径
     * @throws IOException 改名失败时抛出
     */
    private static void move(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // 少数文件系统不支持原子改名，退化成普通覆盖：宁可丢原子性，也不要让结果根本落不了盘
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 删除文件，失败只记 WARN。
     *
     * @param file 文件，可为 {@code null}
     */
    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("清理工具结果临时文件失败: file={} reason={}", file, e.toString());
        }
    }

    /**
     * 按文件数与总字节两个上限从最旧开始清理。
     *
     * @param directory   会话目录
     * @param justWritten 刚写入的文件，永不删除
     * @param settings    工具结果设置
     */
    private static void cleanup(Path directory, Path justWritten, ToolOutputSettings settings) {
        cleanup(directory, justWritten, settings.getKeepFiles(), settings.getMaxBytes());
    }

    /**
     * 按文件数与总字节两个上限从最旧开始清理。
     *
     * @param directory   目标目录
     * @param justWritten 刚写入的文件，永不删除
     * @param keepFiles   最多保留的文件数，{@code 0} 表示不按文件数清理
     * @param maxBytes    最多占用的字节数，{@code 0} 表示不按字节清理
     */
    private static void cleanup(Path directory, Path justWritten, int keepFiles, long maxBytes) {
        if (keepFiles <= 0 && maxBytes <= 0) {
            return;
        }
        List<Path> files = listFiles(directory);
        if (files.size() <= 1) {
            return;
        }
        files.sort(Comparator.comparingLong(ToolOutputStore::lastModified));
        long totalBytes = 0L;
        for (Path file : files) {
            totalBytes += size(file);
        }
        int remaining = files.size();
        for (Path file : files) {
            if (remaining <= 1) {
                break;
            }
            boolean tooMany = keepFiles > 0 && remaining > keepFiles;
            boolean tooLarge = maxBytes > 0 && totalBytes > maxBytes;
            if (!tooMany && !tooLarge) {
                break;
            }
            // 保护刚落盘的那个：清理不该把本轮信封里写着路径的内容删掉；
            // 不靠 mtime 判断，是因为同一毫秒内写多个文件时它分不出先后
            if (file.equals(justWritten)) {
                continue;
            }
            long bytes = size(file);
            try {
                Files.deleteIfExists(file);
                remaining--;
                totalBytes -= bytes;
            } catch (IOException e) {
                LOG.warn("清理旧工具结果失败: file={} reason={}", file, e.toString());
            }
        }
    }

    /**
     * 列举目录下的普通文件，读取失败时返回空列表。
     *
     * @param directory 目录
     * @return 文件列表
     */
    private static List<Path> listFiles(Path directory) {
        List<Path> files = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    files.add(entry);
                }
            }
        } catch (IOException e) {
            LOG.warn("列举工具结果目录失败: dir={} reason={}", directory, e.toString());
        }
        return files;
    }

    /**
     * 取文件最后修改时间，读不到时返回 {@code 0}（等价「最旧」，优先被清理）。
     *
     * @param file 文件
     * @return 毫秒时间戳
     */
    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * 取文件大小，读不到时返回 {@code 0}。
     *
     * @param file 文件
     * @return 字节数
     */
    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * 构造文件名：调用 id + 工具名，非法字符替换、去重路径分隔。
     *
     * @param toolCallId 工具调用标识，可为 {@code null}
     * @param toolName   工具名，可为 {@code null}
     * @param structured 是否结构化结果
     * @return 文件名
     */
    private static String fileName(String toolCallId, String toolName, boolean structured) {
        String id = sanitize(toolCallId == null ? "call" : toolCallId);
        String name = sanitize(toolName == null ? "tool" : toolName);
        return id + '-' + name + (structured ? JSON_SUFFIX : TEXT_SUFFIX);
    }

    /**
     * 清洗路径片段：只保留字母、数字、{@code . _ -}，其余替换；抹掉 {@code ..}。
     * <p>
     * 这两个都要挡住：会话标识与工具调用 id 都来自进程外部（持久化文件 / 模型），
     * 一个写着 {@code ../../x} 的 id 不该把文件写到目录外面去。
     *
     * @param segment 原始片段，可为 {@code null}
     * @return 清洗后的片段，保证非空且长度受限
     */
    static String sanitize(String segment) {
        String value = segment == null ? "" : segment.trim();
        StringBuilder cleaned = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            cleaned.append(allowed ? c : REPLACEMENT);
        }
        String result = cleaned.toString().replace("..", REPLACEMENT);
        if (result.isEmpty()) {
            result = "unnamed";
        }
        return result.length() > MAX_SEGMENT_LENGTH ? result.substring(0, MAX_SEGMENT_LENGTH) : result;
    }
}
