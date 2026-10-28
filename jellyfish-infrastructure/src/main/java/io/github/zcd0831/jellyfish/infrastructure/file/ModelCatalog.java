package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.domain.action.model.ContextLimit;
import io.github.zcd0831.jellyfish.domain.action.model.Model;
import io.github.zcd0831.jellyfish.domain.action.model.ModelCapability;
import io.github.zcd0831.jellyfish.domain.action.model.ModelStatus;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.ProviderId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** models/<id>.json。规则出处：09-infrastructure §9.5。 */
final class ModelCatalog {

    private final DataDirectory dataDirectory;
    private final JsonCodec codec;

    ModelCatalog(DataDirectory dataDirectory, JsonCodec codec) {
        this.dataDirectory = dataDirectory;
        this.codec = codec;
    }

    List<Model> all() {
        List<Model> out = new ArrayList<Model>();
        for (Path file : jsonFiles()) {
            out.add(codec.read(read(file), ModelDto.class).toDomain());
        }
        return out;
    }

    void save(Model model) {
        AtomicFiles.write(dataDirectory.models().resolve(model.id().getValue() + ".json"),
                codec.write(ModelDto.from(model)));
    }

    private List<Path> jsonFiles() {
        List<Path> out = new ArrayList<Path>();
        if (!Files.isDirectory(dataDirectory.models())) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDirectory.models(), "*.json")) {
            for (Path path : stream) {
                out.add(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("扫描模型目录失败", e);
        }
        return out;
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取模型定义失败：" + file, e);
        }
    }

    public static class ModelDto {

        public String id;
        public String providerId;
        public String modelName;
        public String status;
        public LimitDto contextLimit;
        public List<String> capabilities = new ArrayList<String>();

        static ModelDto from(Model model) {
            ModelDto dto = new ModelDto();
            dto.id = model.id().getValue();
            dto.providerId = model.providerId().getValue();
            dto.modelName = model.modelName();
            dto.status = model.status().name();
            dto.contextLimit = new LimitDto();
            dto.contextLimit.maxInputTokens = model.contextLimit().getMaxInputTokens();
            dto.contextLimit.maxOutputTokens = model.contextLimit().getMaxOutputTokens();
            for (ModelCapability capability : model.capabilities()) {
                dto.capabilities.add(capability.getCapabilityName());
            }
            return dto;
        }

        Model toDomain() {
            List<ModelCapability> caps = new ArrayList<ModelCapability>();
            for (String capability : capabilities) {
                caps.add(new ModelCapability(capability));
            }
            return new Model(id == null ? null : ModelId.of(id), ProviderId.of(providerId), modelName,
                    ModelStatus.valueOf(status),
                    new ContextLimit(contextLimit.maxInputTokens, contextLimit.maxOutputTokens), caps);
        }
    }

    public static class LimitDto {
        public int maxInputTokens;
        public int maxOutputTokens;
    }
}
