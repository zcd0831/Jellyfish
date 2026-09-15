package zcd.jellyfish.plugin.sessionfile;

import zcd.jellyfish.api.JellyfishException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话文件仓库：一个会话一个 JSON 文件。
 * <p>
 * <b>一个会话一个文件</b>：git 的 diff 与历史按文件走，把全部会话塞进一个文件会让每次提交都变成
 * 「一大坨」的改动，既看不出改的是哪个会话，也容易冲突。
 * <p>
 * <b>内容没变就不写</b>：内核在每次状态变更后都会派发持久化，而「切换权限模式」这类变更常常没有实际
 * 改动。先比内容再落盘，既省一次写，也避免 git 里出现一串空提交。
 * <p>
 * <b>先写临时文件再原子替换</b>：直接覆盖写时进程被杀死会留下半截 JSON，而下次启动读到坏文件
 * 就等于丢掉整段会话。临时文件用 {@code .json.tmp} 后缀，因此不会被读路径当成会话文件。
 * <p>
 * 文件操作是同步阻塞的，会发生在调用它的线程上（ReAct 回合线程或启动线程）——这是刻意的：
 * 「不可丢」语义要求落盘完成才算这次变更结束。
 *
 * @author zcd
 */
final class SessionStore {

    /** 会话文件后缀。 */
    private static final String SUFFIX = ".json";

    /** 原子替换用的临时后缀，刻意不匹配 {@link #SUFFIX}。 */
    private static final String TEMP_SUFFIX = ".tmp";

    /** 会话文件所在目录。 */
    private final Path directory;

    /**
     * 构造文件仓库。
     *
     * @param directory 会话文件目录，不可为 {@code null}
     */
    SessionStore(Path directory) {
        this.directory = directory;
    }

    /**
     * 取会话对应的文件路径。
     *
     * @param sessionId 会话标识，不可为 {@code null}
     * @return 文件路径
     * @throws JellyfishException 会话标识不能安全地作为文件名时抛出
     */
    Path fileOf(String sessionId) {
        requireSafeFileName(sessionId);
        return directory.resolve(sessionId + SUFFIX);
    }

    /**
     * 在内容确实发生变化时落盘。
     *
     * @param sessionId 会话标识
     * @param json      会话快照 JSON
     * @return 确实写入返回 {@code true}，内容未变返回 {@code false}
     * @throws JellyfishException 写入失败时抛出
     */
    boolean writeIfChanged(String sessionId, String json) {
        Path file = fileOf(sessionId);
        try {
            Files.createDirectories(directory);
            if (Files.exists(file) && json.equals(read(file))) {
                return false;
            }
            writeAtomically(file, json);
            return true;
        } catch (IOException e) {
            throw new JellyfishException("会话落盘失败: " + file + " (" + e.getMessage() + ')', e);
        }
    }

    /**
     * 删除会话文件，文件不存在时静默跳过。
     * <p>
     * <b>幂等</b>：删除一个已经没了的会话应当是成功的，{@code deleteIfExists} 正好表达这个语义；
     * 调用方（插件）据此决定要不要留 git 提交。
     *
     * @param sessionId 会话标识
     * @return 确实删掉了返回 {@code true}；文件本来就不存在返回 {@code false}
     * @throws JellyfishException 删除失败时抛出（与落盘同为「不可丢」语义）
     */
    boolean delete(String sessionId) {
        Path file = fileOf(sessionId);
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new JellyfishException("删除会话文件失败: " + file + " (" + e.getMessage() + ')' , e);
        }
    }

    /**
     * 列出目录下全部会话文件。
     *
     * @return 已排序的会话文件路径列表；目录不存在时为空列表
     * @throws JellyfishException 列举失败时抛出
     */
    List<Path> files() {
        if (!Files.isDirectory(directory)) {
            // 还没落过盘：没有文件不是错误
            return Collections.emptyList();
        }
        List<Path> files = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, '*' + SUFFIX)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    files.add(file);
                }
            }
        } catch (IOException e) {
            throw new JellyfishException("列举会话目录失败: " + directory + " (" + e.getMessage() + ')', e);
        }
        Collections.sort(files);
        return files;
    }

    /**
     * 读取会话文件文本。
     *
     * @param file 文件路径
     * @return 文件内容
     * @throws JellyfishException 读取失败时抛出
     */
    String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JellyfishException("读取会话文件失败: " + file + " (" + e.getMessage() + ')', e);
        }
    }

    /**
     * 原子写入：先写临时文件，再替换目标文件。
     *
     * @param file 目标文件
     * @param json 内容
     * @throws IOException 写入失败时抛出
     */
    private void writeAtomically(Path file, String json) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + TEMP_SUFFIX);
        Files.write(temp, json.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // 少数文件系统不支持原子替换，退化到普通替换：至少不会留下半截文件在目标路径上
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 校验会话标识可以安全地当作文件名。
     * <p>
     * 会话标识会直接拼进文件路径，而它可能来自插件读回的 JSON——也就是外部输入。不做校验的话，
     * 一个写着 {@code ../../x} 的会话标识就能把文件写到目录之外。
     *
     * @param sessionId 会话标识
     * @throws JellyfishException 为空、含路径分隔符或含上跳片段时抛出
     */
    private static void requireSafeFileName(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new JellyfishException("会话标识不能为空");
        }
        if (sessionId.indexOf('/') >= 0 || sessionId.indexOf('\\') >= 0 || sessionId.contains("..")) {
            throw new JellyfishException("会话标识不能作为文件名: " + sessionId);
        }
    }
}
