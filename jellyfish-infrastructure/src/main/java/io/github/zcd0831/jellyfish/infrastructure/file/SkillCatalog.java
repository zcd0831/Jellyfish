package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.domain.memory.skill.InstructionTemplate;
import io.github.zcd0831.jellyfish.domain.memory.skill.SkillDefinition;
import io.github.zcd0831.jellyfish.domain.memory.skill.SkillStatus;
import io.github.zcd0831.jellyfish.domain.shared.SkillId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * skills/&lt;skillId&gt;/SKILL.md：技能没有单独的状态文件，status / mutable 都在 front-matter 里。
 * 规则出处：09-infrastructure §9.5（人写 + 机写混合）。
 */
final class SkillCatalog {

    private static final String BUILTIN_PREFIX = "builtin.";

    private final DataDirectory dataDirectory;

    SkillCatalog(DataDirectory dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    List<SkillDefinition> all() {
        List<SkillDefinition> out = new ArrayList<SkillDefinition>();
        for (Path file : skillFiles(dataDirectory.skills())) {
            out.add(toDomain(file.getParent().getFileName().toString(), FrontMatter.parse(read(file))));
        }
        return out;
    }

    /** 技能写入的唯一落点（08-application §8.7）：front-matter + 正文一起原子重写。 */
    void save(SkillDefinition skill) {
        StringBuilder text = new StringBuilder();
        text.append("---\n");
        text.append("name: ").append(skill.name()).append('\n');
        text.append("description: ").append(skill.description()).append('\n');
        text.append("triggerCondition: ").append(skill.triggerCondition()).append('\n');
        text.append("status: ").append(skill.status().name().toLowerCase()).append('\n');
        text.append("mutable: ").append(skill.isMutable()).append('\n');
        text.append("---\n");
        text.append(skill.sopTemplate().getRawMarkdown());
        AtomicFiles.write(dataDirectory.skills().resolve(skill.id().getValue()).resolve("SKILL.md"), text.toString());
    }

    private static SkillDefinition toDomain(String id, FrontMatter frontMatter) {
        return new SkillDefinition(SkillId.of(id), frontMatter.get("name"), frontMatter.get("description"),
                frontMatter.get("triggerCondition"), new InstructionTemplate(frontMatter.body().trim()),
                status(frontMatter.get("status")), mutable(frontMatter.get("mutable")));
    }

    private static SkillStatus status(String raw) {
        return raw == null ? SkillStatus.ACTIVE : SkillStatus.valueOf(raw.toUpperCase());
    }

    /** 缺省可变：人写技能默认允许系统改进（§7.3 规则 ③）。 */
    private static boolean mutable(String raw) {
        return raw == null || !"false".equalsIgnoreCase(raw.trim());
    }

    private static List<Path> skillFiles(Path dir) {
        List<Path> out = new ArrayList<Path>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path skillDir : stream) {
                Path file = skillDir.resolve("SKILL.md");
                if (Files.isRegularFile(file) && !skillDir.getFileName().toString().startsWith(BUILTIN_PREFIX)) {
                    out.add(file);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("扫描技能目录失败：" + dir, e);
        }
        return out;
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取技能失败：" + file, e);
        }
    }
}
