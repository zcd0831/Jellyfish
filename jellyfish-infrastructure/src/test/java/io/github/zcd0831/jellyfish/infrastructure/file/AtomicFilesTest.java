package io.github.zcd0831.jellyfish.infrastructure.file;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原子替换的落盘协议：临时文件内容落盘 → rename → 目录项落盘。
 * 规则出处：09-infrastructure §9.6。
 *
 * 断言的是「同步动作的序列」，不是内核真的刷了盘（后者无法从进程内观测）；
 * 因此删掉任何一个 sync 调用点，序列就少一项，用例必红。
 */
class AtomicFilesTest {

    @TempDir
    Path tmp;

    private final List<String> events = new ArrayList<String>();

    @AfterEach
    void resetSync() {
        AtomicFiles.useSyncForTest(null);
    }

    /** 替换同步实现，只记录「谁在什么时候被要求落盘」，不改变被测逻辑。 */
    private void recordingSync() {
        AtomicFiles.useSyncForTest(new AtomicFiles.DiskSync() {
            @Override
            public void file(Path path) {
                events.add("file:" + path.getFileName());
            }

            @Override
            public void directory(Path dir) {
                events.add("dir:" + dir.getFileName());
            }
        });
    }

    @Test
    void 原子写先落临时文件内容再落目录项() throws Exception {
        Path target = tmp.resolve("meta.json");
        recordingSync();

        AtomicFiles.write(target, "{\"a\":1}");

        // 顺序即语义：内容先落盘（meta.json.tmp），再 rename，再让目录项落盘
        assertEquals(Arrays.asList("file:meta.json.tmp", "dir:" + tmp.getFileName()), events);
        assertEquals("{\"a\":1}", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
    }

    @Test
    void 原子写完成后不留临时文件() {
        Path target = tmp.resolve("plan.json");

        AtomicFiles.write(target, "{}");

        assertFalse(Files.exists(tmp.resolve("plan.json.tmp")));
    }

    @Test
    void 批量追加只落盘一次文件() throws Exception {
        Path target = tmp.resolve("records-0001.jsonl");
        recordingSync();

        AtomicFiles.appendAll(target, Arrays.asList("{\"n\":1}", "{\"n\":2}", "{\"n\":3}"));

        // 阶段内可批量：三条追加只 fsync 一次（09-infrastructure §9.6 ②）
        assertEquals(Arrays.asList("file:records-0001.jsonl"), events);
        assertEquals(3, Files.readAllLines(target, StandardCharsets.UTF_8).size());
    }

    @Test
    void 追加的每一行都以换行结尾() throws Exception {
        Path target = tmp.resolve("records-0001.jsonl");

        AtomicFiles.append(target, "first");
        AtomicFiles.append(target, "second");

        assertEquals(Arrays.asList("first", "second"), Files.readAllLines(target, StandardCharsets.UTF_8));
        assertTrue(new String(Files.readAllBytes(target), StandardCharsets.UTF_8).endsWith("\n"));
    }

    @Test
    void 内容写入失败不留下已替换的目标文件() {
        Path target = tmp.resolve("locked.json");
        AtomicFiles.useSyncForTest(new AtomicFiles.DiskSync() {
            @Override
            public void file(Path path) {
                throw new java.io.UncheckedIOException(new java.io.IOException("模拟落盘失败"));
            }

            @Override
            public void directory(Path dir) {
            }
        });

        try {
            AtomicFiles.write(target, "{}");
        } catch (RuntimeException expected) {
            // 落盘失败必须外抛，不能静默成功
        }
        // rename 尚未发生：目标文件不存在，或仍是旧内容（这里本来就不存在）
        assertFalse(Files.exists(target));
    }
}
