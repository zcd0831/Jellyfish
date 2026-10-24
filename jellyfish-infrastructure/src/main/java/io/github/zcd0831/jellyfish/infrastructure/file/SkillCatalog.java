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

/** skills/<skillId>/SKILL.md。规则出处：模块-基础设施层 §4（人写资产）。 */
final class SkillCatalog {

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

    void save(SkillDefinition skill) {
        Path dir = dataDirectory.skills().resolve(skill.id().getValue());
        // SOP 正文原样落盘：技能是人写资产，系统不重排格式
        AtomicFiles.write(dir.resolve("SKILL.md"), skill.sopTemplate().getRawMarkdown());
    }

    private static SkillDefinition toDomain(String id, FrontMatter frontMatter) {
        return new SkillDefinition(SkillId.of(id), frontMatter.get("name"), frontMatter.get("description"),
                frontMatter.get("trigger"), new InstructionTemplate(frontMatter.body().trim(),
                frontMatter.list("variables")), status(frontMatter.get("status")));
    }

    private static SkillStatus status(String raw) {
        return raw == null ? SkillStatus.ACTIVE : SkillStatus.valueOf(raw.toUpperCase());
    }

    private static List<Path> skillFiles(Path dir) {
        List<Path> out = new ArrayList<Path>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path skillDir : stream) {
                Path file = skillDir.resolve("SKILL.md");
                if (Files.isRegularFile(file)) {
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
