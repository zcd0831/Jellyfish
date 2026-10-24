package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentSource;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentStatus;
import io.github.zcd0831.jellyfish.domain.action.agent.PromptTemplate;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.SkillId;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** agents/*.md：markdown + front-matter（M04：内置代理在 jar 内，不落盘）。 */
final class AgentCatalog {

    private static final String BUILTIN_PREFIX = "builtin.";

    private final DataDirectory dataDirectory;

    AgentCatalog(DataDirectory dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    List<AgentDefinition> all() {
        List<AgentDefinition> out = new ArrayList<AgentDefinition>();
        for (String path : BuiltinAssets.list("agents/")) {
            FrontMatter frontMatter = FrontMatter.parse(BuiltinAssets.readText("/builtin/" + path));
            out.add(toDomain(idOf(fileName(path)), frontMatter, AgentSource.BUILT_IN));
        }
        for (Path file : marks(dataDirectory.agents())) {
            String id = idOf(file.getFileName().toString());
            if (id.startsWith(BUILTIN_PREFIX)) {
                continue;   // 内置 id 前缀保留
            }
            out.add(toDomain(id, FrontMatter.parse(read(file)), AgentSource.USER_DEFINED));
        }
        return out;
    }

    void save(AgentDefinition agent) {
        StringBuilder text = new StringBuilder();
        text.append("---\n");
        text.append("name: ").append(agent.name()).append('\n');
        text.append("tools: ").append(joinToolIds(agent)).append('\n');
        text.append("skills: [").append(join(agent.listBoundSkillIds())).append("]\n");
        text.append("status: ").append(agent.status().name().toLowerCase()).append('\n');
        text.append("---\n");
        text.append(agent.basePrompt().getTemplateText());
        AtomicFiles.write(dataDirectory.agents().resolve(agent.id().getValue() + ".md"), text.toString());
    }

    private static String joinToolIds(AgentDefinition agent) {
        List<String> ids = new ArrayList<String>();
        for (ToolId toolId : agent.listBoundToolIds()) {
            ids.add(toolId.getValue());
        }
        return "[" + join(ids) + "]";
    }

    private static String join(List<?> values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(String.valueOf(values.get(i)));
        }
        return out.toString();
    }

    private static AgentDefinition toDomain(String id, FrontMatter frontMatter, AgentSource source) {
        List<ToolId> toolIds = new ArrayList<ToolId>();
        for (String toolId : frontMatter.list("tools")) {
            toolIds.add(ToolId.of(toolId));
        }
        List<SkillId> skillIds = new ArrayList<SkillId>();
        for (String skillId : frontMatter.list("skills")) {
            skillIds.add(SkillId.of(skillId));
        }
        return new AgentDefinition(AgentId.of(id), frontMatter.get("name"), source,
                new PromptTemplate(frontMatter.body().trim(), new ArrayList<String>()),
                status(frontMatter.get("status")), toolIds, skillIds);
    }

    private static AgentStatus status(String raw) {
        return raw == null ? AgentStatus.ACTIVE : AgentStatus.valueOf(raw.toUpperCase());
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String idOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    private static List<Path> marks(Path dir) {
        List<Path> out = new ArrayList<Path>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.md")) {
            for (Path path : stream) {
                out.add(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("扫描代理目录失败：" + dir, e);
        }
        return out;
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取代理定义失败：" + file, e);
        }
    }
}
