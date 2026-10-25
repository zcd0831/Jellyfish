package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.application.action.port.PluginRepository;
import io.github.zcd0831.jellyfish.domain.action.plugin.PluginManifest;
import io.github.zcd0831.jellyfish.domain.shared.PluginId;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * `plugins/<id>/plugin.json`：只存用户意图（信任 / 勾选的能力），"已加载"不落盘（M42、D24-4）。
 * 规则出处：模块-基础设施层 §3、§4。
 */
public final class FilePluginRepository implements PluginRepository {

    private final DataDirectory dataDirectory;
    private final JsonCodec codec;

    public FilePluginRepository(DataDirectory dataDirectory, JsonCodec codec) {
        this.dataDirectory = dataDirectory;
        this.codec = codec;
    }

    @Override
    public List<PluginManifest> list() {
        List<PluginManifest> out = new ArrayList<PluginManifest>();
        if (!Files.isDirectory(dataDirectory.plugins())) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDirectory.plugins())) {
            for (Path dir : stream) {
                Path file = dir.resolve("plugin.json");
                if (Files.isRegularFile(file)) {
                    out.add(toDomain(dir.getFileName().toString(), codec.read(read(file), PluginDto.class)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("扫描插件目录失败：" + dataDirectory.plugins(), e);
        }
        return out;
    }

    @Override
    public PluginManifest load(PluginId pluginId) {
        Path file = dataDirectory.plugins().resolve(pluginId.getValue()).resolve("plugin.json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return toDomain(pluginId.getValue(), codec.read(read(file), PluginDto.class));
    }

    @Override
    public void save(PluginManifest manifest) {
        AtomicFiles.write(dataDirectory.plugins().resolve(manifest.id().getValue()).resolve("plugin.json"),
                codec.write(PluginDto.from(manifest)));
    }

    private static PluginManifest toDomain(String id, PluginDto dto) {
        Set<ToolId> tools = new LinkedHashSet<ToolId>();
        for (String toolId : dto.toolIds) {
            tools.add(ToolId.of(toolId));
        }
        return new PluginManifest(PluginId.of(id), dto.version, dto.apiVersion, dto.trusted,
                dto.grantedAt == null ? null : new Date(dto.grantedAt), tools,
                new LinkedHashSet<String>(dto.declaredCapabilities),
                new LinkedHashSet<String>(dto.grantedCapabilities));
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取插件清单失败：" + file, e);
        }
    }

    /** 纯 JSON 形态（D07：camelCase、不写 null、epoch 毫秒）。 */
    public static class PluginDto {

        public String version;
        public int apiVersion;
        public boolean trusted;
        public Long grantedAt;
        public List<String> toolIds = new ArrayList<String>();
        public List<String> declaredCapabilities = new ArrayList<String>();
        public List<String> grantedCapabilities = new ArrayList<String>();

        static PluginDto from(PluginManifest manifest) {
            PluginDto dto = new PluginDto();
            dto.version = manifest.version();
            dto.apiVersion = manifest.apiVersion();
            dto.trusted = manifest.isTrusted();
            dto.grantedAt = manifest.grantedAt() == null ? null : manifest.grantedAt().getTime();
            for (ToolId toolId : manifest.toolIds()) {
                dto.toolIds.add(toolId.getValue());
            }
            dto.declaredCapabilities.addAll(manifest.declaredCapabilities());
            dto.grantedCapabilities.addAll(manifest.grantedCapabilities());
            return dto;
        }
    }
}
