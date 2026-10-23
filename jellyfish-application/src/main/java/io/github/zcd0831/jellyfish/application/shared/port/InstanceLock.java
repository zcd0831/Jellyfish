package io.github.zcd0831.jellyfish.application.shared.port;

/**
 * 进程级单写者锁（FileChannel.tryLock）。规则出处：技术决策 D05。
 */
public interface InstanceLock {

    void acquire();

    void release();
}
