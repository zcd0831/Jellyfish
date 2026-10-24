package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.application.shared.port.ArtifactStore;
import io.github.zcd0831.jellyfish.domain.shared.RecordId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 工具结果全文归档；会话销毁时随目录删除。
 * 规则出处：模块-行动 §7.1 规则 6、模块-基础设施层 §3。
 */
public final class FileArtifactStore implements ArtifactStore {

    private final SessionDirs sessionDirs;

    public FileArtifactStore(DataDirectory dataDirectory) {
        this.sessionDirs = new SessionDirs(dataDirectory);
    }

    @Override
    public String put(SessionId sessionId, RecordId recordId, byte[] content) {
        Path target = sessionDirs.of(sessionId.getValue())
                .resolve("artifacts")
                .resolve(recordId.getValue() + ".txt");
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException("归档工具结果失败：" + target, e);
        }
        return target.toAbsolutePath().toString();
    }

    @Override
    public void deleteSession(SessionId sessionId) {
        Path artifacts = sessionDirs.of(sessionId.getValue()).resolve("artifacts");
        try {
            if (!Files.isDirectory(artifacts)) {
                return;
            }
            Files.walk(artifacts)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("清理归档失败：" + artifacts, e);
        }
    }
}
