package io.github.zcd0831.jellyfish.application.shared.port;

/**
 * 顺序执行内置迁移，不自动改结构。规则出处：09-infrastructure §9.10。
 */
public interface SchemaMigrator {

    void migrate();
}
