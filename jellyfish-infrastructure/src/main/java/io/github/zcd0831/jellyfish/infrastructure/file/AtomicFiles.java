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
public final class AtomicFiles {

    private AtomicFiles() {
    }

    /** 并发创建同一目录时 createDirectories 可能抛 FileAlreadyExists —— 目录已存在就是成功。 */
    private static void ensureParent(Path target) throws IOException {
        try {
            Files.createDirectories(target.getParent());
        } catch (java.nio.file.FileAlreadyExistsException alreadyThere) {
            // 另一个写者刚建好，直接继续
        }
    }

    /** 同一路径的写入串行化：子代理并行改同一个 budget.json 时不能互相踩临时文件。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> LOCKS =
            new java.util.concurrent.ConcurrentHashMap<String, Object>();

    private static Object lockOf(Path target) {
        String key = target.toAbsolutePath().normalize().toString();
        Object lock = LOCKS.get(key);
        if (lock == null) {
            Object created = new Object();
            lock = LOCKS.putIfAbsent(key, created);
            if (lock == null) {
                lock = created;
            }
        }
        return lock;
    }

    public static void write(Path target, String content) {
        synchronized (lockOf(target)) {
            writeLocked(target, content);
        }
    }

    private static void writeLocked(Path target, String content) {
        try {
            ensureParent(target);
            Path tmp = target.resolveSibling(target.getFileName() + "." + Thread.currentThread().getId() + ".tmp");
            Files.write(tmp, content.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("write failed: " + target, e);
        }
    }

    static void append(Path target, String line) {
        try {
            ensureParent(target);
            Files.write(target, (line + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("append failed: " + target, e);
        }
    }
}
