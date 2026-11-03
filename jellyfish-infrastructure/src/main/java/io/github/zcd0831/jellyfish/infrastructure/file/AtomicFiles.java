package io.github.zcd0831.jellyfish.infrastructure.file;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;

/**
 * 原子写：临时文件同目录生成 → fsync → rename → fsync 目录。
 * 规则出处：09-infrastructure §9.6（write / append 的 fsync 协议）、§9.6「目录 fsync 的平台差异」。
 */
public final class AtomicFiles {

    /**
     * 同一路径的写入必须串行化（子代理并行改同一个 budget.json 时不能互相踩临时文件）。
     * 用固定条带锁而不是按路径建表：路径表只增不减，长跑必涨。
     * 规则出处：09-infrastructure §9.6「同一路径的写入按目标路径串行化」。
     */
    private static final int LOCK_STRIPES = 64;
    private static final Object[] LOCKS = new Object[LOCK_STRIPES];

    static {
        for (int i = 0; i < LOCK_STRIPES; i++) {
            LOCKS[i] = new Object();
        }
    }

    /**
     * 落盘同步端口。fsync 本身不可从进程内观测，所以把「同步动作」收敛成唯一出口，
     * 由调用方按阶段顺序触发；测试替换成记录实现后，可断言序列与次数。
     * 注意：端口只包 fsync 这一个动作，不含任何分支逻辑 —— 替换它不会替掉被测的协议。
     */
    interface DiskSync {
        void file(Path path);

        void directory(Path dir);
    }

    /** 目录 fsync 是否被平台支持：null = 尚未探测，false = 已降级。 */
    private static volatile Boolean directorySyncSupported;

    private static final DiskSync REAL_SYNC = new DiskSync() {
        @Override
        public void file(Path path) {
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
                channel.force(false);   // force(false) = fdatasync：内容落盘，不刷无关元数据
            } catch (IOException e) {
                throw new UncheckedIOException("fsync failed: " + path, e);
            }
        }

        @Override
        public void directory(Path dir) {
            if (dir == null) {
                return;
            }
            if (Boolean.FALSE.equals(directorySyncSupported)) {
                return;   // 已降级：不再重复尝试（09-infrastructure §9.6 平台降级）
            }
            try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
                channel.force(true);
                directorySyncSupported = Boolean.TRUE;
            } catch (IOException e) {
                if (directorySyncSupported == null) {
                    // 首次探测即失败：该平台没有目录 fsync 能力，降级但不阻断（09-infrastructure §9.6）
                    directorySyncSupported = Boolean.FALSE;
                    return;
                }
                throw new UncheckedIOException("fsync dir failed: " + dir, e);
            }
        }
    };

    private static volatile DiskSync sync = REAL_SYNC;

    private AtomicFiles() {
    }

    /** 测试专用：替换同步实现。传 null 复位为真实实现。 */
    static void useSyncForTest(DiskSync replacement) {
        sync = replacement == null ? REAL_SYNC : replacement;
        directorySyncSupported = null;
    }

    private static Object lockOf(Path target) {
        String key = target.toAbsolutePath().normalize().toString();
        return LOCKS[(key.hashCode() & 0x7fffffff) % LOCK_STRIPES];
    }

    /** 并发创建同一目录时 createDirectories 可能抛 FileAlreadyExists —— 目录已存在就是成功。 */
    private static void ensureParent(Path target) throws IOException {
        try {
            Files.createDirectories(target.getParent());
        } catch (java.nio.file.FileAlreadyExistsException alreadyThere) {
            // 另一个写者刚建好，直接继续
        }
    }

    /** 原子替换：tmp 内容落盘后才 rename，rename 后再让目录项落盘。 */
    public static void write(Path target, String content) {
        synchronized (lockOf(target)) {
            try {
                ensureParent(target);
                // 固定 ".tmp" 后缀（09-infrastructure §9.6：tmp = path + ".tmp"）；
                // 同路径已在条带锁内串行，不需要线程 id 进文件名。
                Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                Files.write(tmp, content.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                sync.file(tmp);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                sync.directory(target.getParent());
            } catch (IOException e) {
                throw new UncheckedIOException("write failed: " + target, e);
            }
        }
    }

    /** 追加一行并落盘（09-infrastructure §9.6 append 协议：单写者保证不交错）。 */
    static void append(Path target, String line) {
        appendAll(target, Collections.singletonList(line));
    }

    /**
     * 批量追加后只 fsync 一次。
     * 规则出处：09-infrastructure §9.6「② 所有 stageAppend → 追加；全部写完后 fsync 记录文件」——
     * 阶段内可批量，阶段间不可合并。
     */
    static void appendAll(Path target, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        synchronized (lockOf(target)) {
            try {
                ensureParent(target);
                StringBuilder batch = new StringBuilder();
                for (String line : lines) {
                    batch.append(line).append('\n');
                }
                Files.write(target, batch.toString().getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
                sync.file(target);
            } catch (IOException e) {
                throw new UncheckedIOException("append failed: " + target, e);
            }
        }
    }
}
