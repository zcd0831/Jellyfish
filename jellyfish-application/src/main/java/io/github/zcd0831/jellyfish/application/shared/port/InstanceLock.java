package io.github.zcd0831.jellyfish.application.shared.port;

/**
 * 进程级单写者锁（FileChannel.tryLock）。。
 */
public interface InstanceLock {

    void acquire();

    void release();
}
