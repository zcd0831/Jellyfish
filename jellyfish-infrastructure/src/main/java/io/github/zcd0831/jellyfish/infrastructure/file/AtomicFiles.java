package io.github.zcd0831.jellyfish.infrastructure.file;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 原子写：临时文件同目录生成 → fsync → rename → fsync 目录。
 * 规则出处：模块-基础设施层 §5。
 */
final class AtomicFiles {

    private AtomicFiles() {
    }

    static void write(Path target, String content) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, content.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("write failed: " + target, e);
        }
    }

    static void append(Path target, String line) {
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, (line + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("append failed: " + target, e);
        }
    }
}
