package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.application.memory.port.SkillSopProvider;
import io.github.zcd0831.jellyfish.domain.memory.skill.SkillDefinition;

import java.util.Collections;

/**
 * `load_skill` 的 SOP 读取：按名字找 ACTIVE 技能并渲染正文；找不到返回空（不编造说明文字）。
 * 规则出处：07-memory §7.9.4、09-infrastructure §9.4。
 */
public final class FileSkillSopProvider implements SkillSopProvider {

    private final FileCatalogRepository catalog;

    public FileSkillSopProvider(FileCatalogRepository catalog) {
        this.catalog = catalog;
    }

    @Override
    public java.util.Optional<String> sop(String skillId) {
        for (SkillDefinition skill : catalog.skills()) {
            if (!skill.isActive()) {
                continue;
            }
            if (skill.id().getValue().equals(skillId) || skill.name().equals(skillId)) {
                return java.util.Optional.of(
                        skill.sopTemplate() == null ? "" : skill.sopTemplate().getRawMarkdown());
            }
        }
        return java.util.Optional.empty();
    }
}
