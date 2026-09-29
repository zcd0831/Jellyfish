package zcd.jellyfish.plugin.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 二进制内容的落盘：把 {@code image} / {@code audio} 这类内容写进临时目录并给出路径。
 * <p>
 * <b>为什么不直接把 base64 塞进工具输出</b>：base64 的体积是原文件的 4/3，而一个截图动辄几百 KB，
 * 它会被当成文本回灌给模型、写进会话快照、参与上下文裁剪——一次工具调用就能把整个窗口撑满。
 * 落盘之后回灌的只有「一个路径」，模型需要时自己决定要不要读。
 * <p>
 * <b>为什么目录带 PID</b>：每个 jellyfish 进程一个目录，插件停止时把自己那一个整棵删掉即可，
 * 不会误删另一个还在跑的进程留下的文件。{@link #cleanup()} 因此是「删自己的」而不是「清理全局」。
 * <p>
 * <b>写不成不抛异常</b>：磁盘不可写不该把「一次工具调用」升级成故障——调用点拿到 {@code null}
 * 之后会退回「只报告体积」的文本。这与内核「落盘失败不是回合失败」是同一条取舍。
 * <p>
 * 线程安全。
 *
 * @author zcd
 */
final class McpMediaSpill {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(McpMediaSpill.class);

    /** 单条内容允许落盘的上限：超过就只报告体积。 */
    private static final int MAX_SPILL_BYTES = 16 * 1024 * 1024;

    /** 常见 MIME 类型到扩展名的映射。 */
    private static final Map<String, String> EXTENSIONS;

    static {
        Map<String, String> extensions = new LinkedHashMap<String, String>();
        extensions.put("image/png", "png");
        extensions.put("image/jpeg", "jpg");
        extensions.put("image/gif", "gif");
        extensions.put("image/webp", "webp");
        extensions.put("image/svg+xml", "svg");
        extensions.put("image/bmp", "bmp");
        extensions.put("audio/wav", "wav");
        extensions.put("audio/mpeg", "mp3");
        extensions.put("audio/ogg", "ogg");
        extensions.put("application/pdf", "pdf");
        EXTENSIONS = Collections.unmodifiableMap(extensions);
    }

    /** 本次进程的落盘目录。 */
    private final Path baseDirectory;

    /**
     * 构造落盘器。
     *
     * @param baseDirectory 本次进程的落盘根目录，不可为 {@code null}
     */
    McpMediaSpill(Path baseDirectory) {
        this.baseDirectory = baseDirectory;
    }

    /**
     * 构造默认落盘目录：系统临时目录下的 {@code jellyfish-mcp/<pid>}。
     *
     * @return 落盘目录
     */
    static Path defaultBaseDirectory() {
        String tmp = System.getProperty("java.io.tmpdir");
        Path root = (tmp == null || tmp.trim().isEmpty())
                ? new java.io.File(".").toPath().toAbsolutePath()
                : new java.io.File(tmp).toPath().toAbsolutePath();
        return root.resolve("jellyfish-mcp").resolve(processId());
    }

    /**
     * 落盘一段 base64 内容。
     *
     * @param base64Data base64 文本，可为 {@code null}
     * @param mimeType   MIME 类型，可为 {@code null}
     * @param serverId   服务标识，用于文件名
     * @param toolName   工具名，用于文件名
     * @return 落盘后的绝对路径；未落盘（无内容、超上限、解码失败或写入失败）时返回 {@code null}
     */
    String spill(String base64Data, String mimeType, String serverId, String toolName) {
        if (base64Data == null || base64Data.isEmpty()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Data);
        } catch (IllegalArgumentException e) {
            LOG.warn("MCP 二进制内容不是合法 base64，已跳过落盘: server={} tool={}", serverId, toolName);
            return null;
        }
        if (bytes.length > MAX_SPILL_BYTES) {
            LOG.warn("MCP 二进制内容超过落盘上限，仅报告体积: server={} tool={} bytes={}", serverId,
                    toolName, Integer.valueOf(bytes.length));
            return null;
        }
        Path target = baseDirectory.resolve(fileName(serverId, toolName, mimeType));
        try {
            Files.createDirectories(baseDirectory);
            Files.write(target, bytes);
            return target.toAbsolutePath().toString();
        } catch (IOException | RuntimeException e) {
            LOG.warn("MCP 二进制内容落盘失败，仅报告体积: server={} tool={}", serverId, toolName, e);
            return null;
        }
    }

    /**
     * 删除本次进程的整个落盘目录。
     * <p>
     * 只在插件停止时调用一次。删不掉只记 WARN：残留几个临时文件不该阻止插件停止。
     */
    void cleanup() {
        if (!Files.exists(baseDirectory)) {
            return;
        }
        try {
            java.util.List<Path> paths = new java.util.ArrayList<Path>();
            try (java.util.stream.Stream<Path> stream = Files.walk(baseDirectory)) {
                for (Path path : (Iterable<Path>) stream::iterator) {
                    paths.add(path);
                }
            }
            Collections.reverse(paths);
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
            LOG.info("MCP 落盘目录已清理: {}", baseDirectory);
        } catch (IOException | RuntimeException e) {
            LOG.warn("MCP 落盘目录清理失败（残留临时文件）: {}", baseDirectory, e);
        }
    }

    /**
     * 组装落盘文件名。
     *
     * @param serverId  服务标识
     * @param toolName  工具名
     * @param mimeType  MIME 类型
     * @return 文件名
     */
    private static String fileName(String serverId, String toolName, String mimeType) {
        String extension = EXTENSIONS.get(mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT));
        if (extension == null) {
            extension = "bin";
        }
        return McpToolName.sanitize(serverId) + "-" + McpToolName.sanitize(toolName) + "-"
                + System.nanoTime() + "." + extension;
    }

    /**
     * 取当前进程标识，用于隔离不同进程的落盘目录。
     *
     * @return 进程标识文本
     */
    private static String processId() {
        try {
            return String.valueOf(java.lang.management.ManagementFactory.getRuntimeMXBean()
                    .getName().split("@")[0]);
        } catch (RuntimeException e) {
            return "unknown";
        }
    }
}
