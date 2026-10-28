package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.application.shared.port.SchemaMigrator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 只做向前迁移；不自动改结构。
 * 规则出处：09-infrastructure §9.10。
 */
public final class FileSchemaMigrator implements SchemaMigrator {

    private static final int CURRENT_VERSION = 1;

    private final DataDirectory dir;

    public FileSchemaMigrator(DataDirectory dir) {
        this.dir = dir;
    }

    @Override
    public void migrate() {
        Path file = dir.schemaVersionFile();
        try {
            Files.createDirectories(dir.root());
            if (!Files.exists(file)) {
                AtomicFiles.write(file, String.valueOf(CURRENT_VERSION));
                return;
            }
            int found = Integer.parseInt(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim());
            if (found > CURRENT_VERSION) {
                throw new IllegalStateException("数据目录来自更新的版本（schema " + found + "），请升级程序");
            }
            if (found < CURRENT_VERSION) {
                AtomicFiles.write(file, String.valueOf(CURRENT_VERSION));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("migrate failed", e);
        }
    }
}
