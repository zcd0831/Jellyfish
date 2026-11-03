package io.github.zcd0831.jellyfish.infrastructure.file;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * 启动技术清理：只处理**文件层面的残留**，不碰业务语义（业务不一致由 StartupReconciler 负责）。
 * 规则出处：09-infrastructure §9.7。
 * <p>必须在拿到 InstanceLock 之后、任何读取之前执行。
 */
public final class FileSystemCleaner {

    private final DataDirectory dir;

    public FileSystemCleaner(DataDirectory dir) {
        this.dir = dir;
    }

    /** 执行清理，返回需要告警的条目（不阻断启动）。 */
    public List<String> clean() {
        List<String> warnings = new ArrayList<String>();
        deleteArchivedSessions(warnings);
        deleteTempFiles(warnings);
        truncateHalfLines(warnings);
        warnNestedSubDirs(warnings);
        warnPluginIdMismatch(warnings);
        return warnings;
    }

    /** 09 §9.7：archive/&lt;id&gt;.zip 已存在但 sessions/&lt;id&gt;/ 仍在 ⇒ 删除会话目录（含其 sub/）。 */
    private void deleteArchivedSessions(List<String> warnings) {
        if (!Files.isDirectory(dir.sessions())) {
            return;
        }
        for (Path session : listDirs(dir.sessions())) {
            Path archived = dir.archive().resolve(session.getFileName().toString() + ".zip");
            if (!Files.isRegularFile(archived)) {
                continue;
            }
            deleteRecursively(session);
            warnings.add("已归档会话目录仍在，已删除：" + session.getFileName());
        }
    }

    /** 09 §9.7：*.tmp 直接删除（rename 未完成 ⇒ 旧文件仍有效）。 */
    private void deleteTempFiles(List<String> warnings) {
        if (!Files.isDirectory(dir.root())) {
            return;
        }
        for (Path temp : walk(dir.root(), ".tmp")) {
            try {
                Files.deleteIfExists(temp);
                warnings.add("删除未完成的临时文件：" + temp.getFileName());
            } catch (IOException e) {
                warnings.add("临时文件删除失败，忽略：" + temp);
            }
        }
    }

    /** 09 §9.7：records-NNNN.jsonl 末尾半行（无换行）⇒ 截断到最后一条完整行。 */
    private void truncateHalfLines(List<String> warnings) {
        if (!Files.isDirectory(dir.sessions())) {
            return;
        }
        for (Path segment : walk(dir.sessions(), ".jsonl")) {
            if (!segment.getFileName().toString().startsWith("records-")) {
                continue;
            }
            try {
                if (truncateToLastCompleteLine(segment)) {
                    warnings.add("截断未写完的记录行：" + segment.getFileName());
                }
            } catch (IOException e) {
                warnings.add("记录文件截断失败，忽略：" + segment);
            }
        }
    }

    /** 09 §9.7：sub/ 下出现第二层 sub/ ⇒ 违反"子代理不得再开子代理"，告警并忽略该目录。 */
    private void warnNestedSubDirs(List<String> warnings) {
        if (!Files.isDirectory(dir.sessions())) {
            return;
        }
        for (Path root : listDirs(dir.sessions())) {
            for (Path child : listDirs(root.resolve("sub"))) {
                if (Files.isDirectory(child.resolve("sub"))) {
                    warnings.add("子会话下出现第二层 sub/，已忽略：" + child.getFileName());
                }
            }
        }
    }

    /** 09 §9.7：plugins/&lt;id&gt;/ 目录名与 jar 内 Plugin-Id 不一致 ⇒ 告警并忽略该目录（不装载）。 */
    private void warnPluginIdMismatch(List<String> warnings) {
        if (!Files.isDirectory(dir.plugins())) {
            return;
        }
        for (Path pluginDir : listDirs(dir.plugins())) {
            String directoryName = pluginDir.getFileName().toString();
            String declared = declaredPluginId(pluginDir);
            if (declared == null) {
                warnings.add("插件目录里没有可识别的 jar，已忽略：" + directoryName);
            } else if (!declared.equals(directoryName)) {
                warnings.add("插件目录名与 jar 内 Plugin-Id 不一致，已忽略：" + directoryName + " ≠ " + declared);
            }
        }
    }

    private static String declaredPluginId(Path pluginDir) {
        for (Path jar : walk(pluginDir, ".jar")) {
            try (JarFile jarFile = new JarFile(jar.toFile())) {
                Manifest manifest = jarFile.getManifest();
                if (manifest == null) {
                    continue;
                }
                String id = manifest.getMainAttributes().getValue("Plugin-Id");
                if (id != null && !id.trim().isEmpty()) {
                    return id.trim();
                }
            } catch (IOException e) {
                // 坏 jar 交给装载阶段报错，这里只做到"识别不出就不装载"
            }
        }
        return null;
    }

    /** 返回 true 表示发生了截断。 */
    private static boolean truncateToLastCompleteLine(Path segment) throws IOException {
        byte[] bytes = Files.readAllBytes(segment);
        if (bytes.length == 0 || bytes[bytes.length - 1] == '\n') {
            return false;
        }
        int lastNewline = -1;
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] == '\n') {
                lastNewline = i;
                break;
            }
        }
        byte[] kept = lastNewline < 0 ? new byte[0] : Arrays.copyOf(bytes, lastNewline + 1);
        Files.write(segment, kept, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        return true;
    }

    private static List<Path> listDirs(Path parent) {
        List<Path> out = new ArrayList<Path>();
        if (!Files.isDirectory(parent)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    out.add(entry);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("list dir failed: " + parent, e);
        }
        return out;
    }

    /** 收集指定后缀的文件；不跟随符号链接（避免清理越出数据目录）。 */
    private static List<Path> walk(Path root, final String suffix) {
        final List<Path> out = new ArrayList<Path>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().endsWith(suffix)) {
                        out.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;   // 清理遇到读不了的目录：跳过，不阻断启动
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("walk failed: " + root, e);
        }
        return out;
    }

    private static void deleteRecursively(Path root) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException exc) throws IOException {
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("delete failed: " + root, e);
        }
    }
}
