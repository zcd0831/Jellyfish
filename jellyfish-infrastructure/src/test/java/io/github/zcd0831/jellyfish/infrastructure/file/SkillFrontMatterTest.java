package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.domain.memory.skill.SkillDefinition;
import io.github.zcd0831.jellyfish.domain.memory.skill.SkillStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SKILL.md 的 front-matter 是人手写的：文档示例本身就带行内注释。
 * 规则出处：09-infrastructure §9.5。
 */
class SkillFrontMatterTest {

    @TempDir
    Path tmp;

    private SkillDefinition skill(String frontMatter) throws Exception {
        Path file = DataDirectory.of(tmp.toString()).skills().resolve("learned.a").resolve("SKILL.md");
        Files.createDirectories(file.getParent());
        Files.write(file, frontMatter.getBytes(StandardCharsets.UTF_8));
        FileCatalogRepository catalog = new FileCatalogRepository(DataDirectory.of(tmp.toString()),
                new JsonCodec(), null, null);
        return catalog.skills().get(0);
    }

    @Test
    void 照文档示例写行内注释也能读出各字段() throws Exception {
        SkillDefinition skill = skill("---\n"
                + "name: 依赖升级\n"
                + "description: 升一个依赖并验证回归\n"
                + "triggerCondition: 当需要升级依赖时使用 # 触发说明\n"
                + "status: active # draft / active / deprecated\n"
                + "mutable: false # 锁死\n"
                + "---\n正文\n");

        // 09-infrastructure §9.5 的示例就是这种带注释的写法
        assertEquals(SkillStatus.ACTIVE, skill.status(), "行内注释不能被当成状态值");
        assertEquals(false, skill.isMutable(), "`false # 锁死` 必须读成 false，不能读成 true");
        assertEquals("当需要升级依赖时使用", skill.triggerCondition(), "值里不能带注释尾巴");
    }

    @Test
    void 认不出的状态按草稿处理() throws Exception {
        SkillDefinition skill = skill("---\nname: n\ndescription: d\ntriggerCondition: t\n"
                + "status: activ\n---\n正文\n");

        // fail-closed：写错的技能宁可看不见，也不能被当成启用
        assertEquals(SkillStatus.DRAFT, skill.status());
    }
}
