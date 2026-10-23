package io.github.zcd0831.jellyfish.application.shared.port;

/**
 * 顺序执行内置迁移，不自动改结构。规则出处：模块-基础设施层 §9。
 */
public interface SchemaMigrator {

    void migrate();
}
