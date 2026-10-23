package io.github.zcd0831.jellyfish.infrastructure.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同一数据目录只允许一个写者。
 * 规则出处：技术决策 D05（FileChannel.tryLock）。
 */
class FileInstanceLockTest {

    @TempDir
    Path tmp;

    @Test
    void 第二个实例获取锁会失败() {
        DataDirectory dir = DataDirectory.of(tmp.toString());
        FileInstanceLock first = new FileInstanceLock(dir);
        FileInstanceLock second = new FileInstanceLock(dir);

        first.acquire();
        try {
            assertThrows(IllegalStateException.class, second::acquire);
        } finally {
            first.release();
        }
        assertTrue(tmp.resolve("locks/jellyfish.lock").toFile().exists());
    }
}
