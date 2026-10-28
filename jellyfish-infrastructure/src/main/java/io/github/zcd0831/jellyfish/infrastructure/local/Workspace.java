package io.github.zcd0831.jellyfish.infrastructure.local;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作区根：本地能力只能在这一个目录里动手。
 * 规则出处：05-action §5.8.1 规则 9（根 = 进程启动时的工作目录，判定基于 real path）。
 */
public final class Workspace {

    private final Path root;

    private Workspace(Path root) {
        this.root = root;
    }

    /** 启动时求一次：CWD 是启动事实，不是运行期可变状态。 */
    public static Workspace atLaunchDirectory() {
        return at(Paths.get("").toAbsolutePath());
    }

    public static Workspace at(Path directory) {
        return new Workspace(realPathOf(directory));
    }

    public Path root() {
        return root;
    }

    /**
     * 落到根内的真实路径。目标可以不存在（写新文件）：那时判它的父目录，
     * 否则新建文件永远无法通过 real path 判定。
     */
    public Path resolve(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new OutsideWorkspace("路径为空");
        }
        Path path = Paths.get(raw.trim());
        Path absolute = (path.isAbsolute() ? path : root.resolve(path)).normalize();
        if (!Files.exists(absolute)) {
            return resolveNewTarget(absolute);
        }
        Path real = realPathOf(absolute);
        if (!real.startsWith(root)) {
            throw new OutsideWorkspace("超出工作区：" + raw);
        }
        return real;
    }

    /** 防止 `delete_file`/`recursive` 把工作区自己端掉。 */
    public void refuseRoot(Path path) {
        if (path.equals(root)) {
            throw new OutsideWorkspace("不能对工作区根本身操作");
        }
    }

    public String relative(Path path) {
        Path rel = root.relativize(path);
        return rel.toString().isEmpty() ? "." : rel.toString();
    }

    private Path resolveNewTarget(Path absolute) {
        List<String> missing = new ArrayList<String>();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            missing.add(0, existing.getFileName().toString());
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new OutsideWorkspace("路径无法落地：" + absolute);
        }
        Path resolved = realPathOf(existing);
        for (String name : missing) {
            resolved = resolved.resolve(name);
        }
        if (!resolved.normalize().startsWith(root)) {
            throw new OutsideWorkspace("超出工作区：" + absolute);
        }
        return resolved;
    }

    private static Path realPathOf(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("无法解析路径：" + path, e);
        }
    }

    /** 越界不是异常，是普通工具错误结果（§7.1 规则 9）；这里只用它中断流程。 */
    public static final class OutsideWorkspace extends RuntimeException {

        public OutsideWorkspace(String message) {
            super(message);
        }
    }
}
