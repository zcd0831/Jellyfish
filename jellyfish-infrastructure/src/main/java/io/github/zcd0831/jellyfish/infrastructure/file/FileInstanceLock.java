package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.application.shared.port.InstanceLock;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;

/**
 * 进程级单写者锁；进程退出由 OS 释放。
 */
public final class FileInstanceLock implements InstanceLock {

    private final DataDirectory dir;
    private FileChannel channel;
    private FileLock lock;

    public FileInstanceLock(DataDirectory dir) {
        this.dir = dir;
    }

    @Override
    public void acquire() {
        try {
            Files.createDirectories(dir.locks());
            channel = new RandomAccessFile(dir.locks().resolve("jellyfish.lock").toFile(), "rw").getChannel();
            lock = channel.tryLock();
            if (lock == null) {
                throw new IllegalStateException("另一个 Jellyfish 实例正在使用数据目录：" + dir.root());
            }
        } catch (OverlappingFileLockException e) {
            // 同一进程重复获取也视为"已被占用"，与跨进程语义保持一致
            throw new IllegalStateException("另一个 Jellyfish 实例正在使用数据目录：" + dir.root(), e);
        } catch (IOException e) {
            throw new UncheckedIOException("acquire lock failed", e);
        }
    }

    @Override
    public void release() {
        try {
            if (lock != null) {
                lock.release();
            }
            if (channel != null) {
                channel.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("release lock failed", e);
        }
    }
}
