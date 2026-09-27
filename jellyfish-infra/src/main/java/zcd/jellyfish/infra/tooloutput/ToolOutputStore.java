package zcd.jellyfish.infra.tooloutput;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.config.ToolOutputSettings;
import zcd.jellyfish.infra.support.HomePaths;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
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
 * <b>为什么写在用户主目录</b>（缺省 {@code ~/jellyfish/tool-outputs}）：它是运行产物而非项目内容。
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
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // 少数文件系统不支持原子改名，退化成普通覆盖：宁可丢原子性，也不要让结果根本落不了盘
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 按文件数与总字节两个上限从最旧开始清理。
     *
     * @param directory     会话目录
     * @param justWritten   刚写入的文件，永不删除
     * @param settings      工具结果设置
     */
    private static void cleanup(Path directory, Path justWritten, ToolOutputSettings settings) {
        if (settings.getKeepFiles() <= 0 && settings.getMaxBytes() <= 0) {
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
            boolean tooMany = settings.getKeepFiles() > 0 && remaining > settings.getKeepFiles();
            boolean tooLarge = settings.getMaxBytes() > 0 && totalBytes > settings.getMaxBytes();
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
