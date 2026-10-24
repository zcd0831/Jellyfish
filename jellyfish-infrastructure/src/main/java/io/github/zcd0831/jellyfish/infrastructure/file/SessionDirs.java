package io.github.zcd0831.jellyfish.infrastructure.file;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * sessionId → 目录。子会话嵌套在 sub/ 下，深度 ≤ 2（D05.1），所以扫两层即可，不需要索引。
 * 规则出处：模块-基础设施层 §4、技术决策 D05.1。
 */
final class SessionDirs {

    private final DataDirectory dataDirectory;

    SessionDirs(DataDirectory dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    Path of(String sessionId) {
        Path direct = dataDirectory.sessionDir(sessionId);
        if (Files.isDirectory(direct)) {
            return direct;
        }
        if (!Files.isDirectory(dataDirectory.sessions())) {
            throw new IllegalArgumentException("会话目录尚未建立：" + sessionId);
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDirectory.sessions())) {
            for (Path root : stream) {
                Path child = root.resolve("sub").resolve(sessionId);
                if (Files.isDirectory(child)) {
                    return child;
                }
            }
        } catch (NoSuchFileException gone) {
            // 并发创建/删除 sub/ 时目录可能瞬间不可见，按"尚未建立"处理
            throw new IllegalArgumentException("会话目录尚未建立：" + sessionId, gone);
        } catch (IOException e) {
            throw new UncheckedIOException("定位会话目录失败：" + sessionId, e);
        }
        throw new IllegalArgumentException("会话目录不存在：" + sessionId);
    }
}
