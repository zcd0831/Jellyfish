package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.domain.action.tool.SideEffectLevel;
import io.github.zcd0831.jellyfish.domain.action.tool.SourceType;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolDefinition;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolSchema;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolSource;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolStatus;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** tools/<id>.json：jar 内置 + 磁盘用户资产（M04）。 */
final class ToolCatalog {

    private static final String BUILTIN_PREFIX = "builtin.";

    private final DataDirectory dataDirectory;
    private final JsonCodec codec;

    ToolCatalog(DataDirectory dataDirectory, JsonCodec codec) {
        this.dataDirectory = dataDirectory;
        this.codec = codec;
    }

    List<ToolDefinition> all() {
        List<ToolDefinition> out = new ArrayList<ToolDefinition>();
        for (String path : BuiltinAssets.list("tools/")) {
            ToolDto dto = codec.read(BuiltinAssets.readText("/builtin/" + path), ToolDto.class);
            out.add(dto.toDomain());
        }
        for (Path file : jsonFiles(dataDirectory.tools())) {
            ToolDto dto = codec.read(read(file), ToolDto.class);
            if (dto.id != null && dto.id.startsWith(BUILTIN_PREFIX)) {
                continue;   // 内置 id 前缀保留，用户文件不得覆盖内置定义
            }
            out.add(dto.toDomain());
        }
        return out;
    }

    void save(ToolDefinition tool) {
        ToolDto dto = ToolDto.from(tool);
        AtomicFiles.write(dataDirectory.tools().resolve(tool.id().getValue() + ".json"), codec.write(dto));
    }

    private static List<Path> jsonFiles(Path dir) {
        List<Path> out = new ArrayList<Path>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path path : stream) {
                out.add(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("扫描工具目录失败：" + dir, e);
        }
        return out;
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取工具定义失败：" + file, e);
        }
    }

    public static class ToolDto {

        public String id;
        public String name;
        public String description;
        public JsonNode inputSchema;
        public JsonNode outputSchema;
        public SourceDto source;
        public String sideEffect;
        public int timeoutMillis;
        public String status;

        static ToolDto from(ToolDefinition tool) {
            ToolDto dto = new ToolDto();
            dto.id = tool.id().getValue();
            dto.name = tool.name();
            dto.description = tool.description();
            dto.inputSchema = tree(tool.inputSchema().getJsonSchemaString());
            dto.outputSchema = tool.outputSchema() == null ? null : tree(tool.outputSchema().getJsonSchemaString());
            dto.source = new SourceDto();
            dto.source.type = tool.source().getType().name();
            dto.source.endpointOrIdentifier = tool.source().getEndpointOrIdentifier();
            dto.sideEffect = tool.sideEffect().name();
            dto.timeoutMillis = tool.timeoutMillis();
            dto.status = tool.status().name();
            return dto;
        }

        ToolDefinition toDomain() {
            return new ToolDefinition(ToolId.of(id), name, description, new ToolSchema(text(inputSchema)),
                    new ToolSchema(text(outputSchema)),
                    new ToolSource(SourceType.valueOf(source.type), source.endpointOrIdentifier),
                    SideEffectLevel.valueOf(sideEffect), timeoutMillis, ToolStatus.valueOf(status));
        }

        private static String text(JsonNode node) {
            return node == null ? "" : node.toString();
        }

        private static JsonNode tree(String schemaJson) {
            try {
                return schemaJson == null || schemaJson.trim().isEmpty()
                        ? null : new ObjectMapper().readTree(schemaJson);
            } catch (IOException e) {
                throw new UncheckedIOException("工具 schema 不是合法 JSON", e);
            }
        }
    }

    public static class SourceDto {
        public String type;
        public String endpointOrIdentifier;
    }
}
