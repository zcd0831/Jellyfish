package io.github.zcd0831.jellyfish.infrastructure.file;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 数据目录布局；路径解析完全属于基础设施层。
 * 规则出处：模块-基础设施层 §4、技术决策 D05.1。
 */
public final class DataDirectory {

    private final Path root;

    public DataDirectory(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public static DataDirectory of(String path) {
        return new DataDirectory(Paths.get(path));
    }

    public Path root() {
        return root;
    }

    public Path sessions() {
        return root.resolve("sessions");
    }

    public Path sessionDir(String sessionId) {
        return sessions().resolve(sessionId);
    }

    public Path subDirOf(String rootSessionId, String childId) {
        return sessionDir(rootSessionId).resolve("sub").resolve(childId);
    }

    public Path archive() {
        return root.resolve("archive");
    }

    public Path agents() {
        return root.resolve("agents");
    }

    public Path skills() {
        return root.resolve("skills");
    }

    public Path tools() {
        return root.resolve("tools");
    }

    public Path providers() {
        return root.resolve("providers");
    }

    public Path models() {
        return root.resolve("models");
    }

    public Path plugins() {
        return root.resolve("plugins");
    }

    public Path experience() {
        return root.resolve("experience");
    }

    public Path locks() {
        return root.resolve("locks");
    }

    public Path logs() {
        return root.resolve("logs");
    }

    public Path settingsFile() {
        return root.resolve("settings.json");
    }

    public Path schemaVersionFile() {
        return root.resolve("schema.version");
    }
}
