package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.application.memory.port.ExperienceRepository;
import io.github.zcd0831.jellyfish.domain.memory.experience.ConfidenceScore;
import io.github.zcd0831.jellyfish.domain.memory.experience.Experience;
import io.github.zcd0831.jellyfish.domain.memory.experience.ExperienceStatus;
import io.github.zcd0831.jellyfish.domain.memory.experience.LessonLearned;
import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * experience/<id>.json：全局资产，只新建；合并 = 改内容 + 提置信度；归档保留供审计。
 * 容量淘汰由应用层按 `createdAt` 升序 + `appliedCount` 升序执行（07-memory §7.8.1 规则 6），本层只提供数据。
 * 规则出处：09-infrastructure §9.4、§9.5。
 */
public final class FileExperienceRepository implements ExperienceRepository {

    private final DataDirectory dataDirectory;
    private final JsonCodec codec;

    public FileExperienceRepository(DataDirectory dataDirectory, JsonCodec codec) {
        this.dataDirectory = dataDirectory;
        this.codec = codec;
    }

    @Override
    public List<Experience> findActive() {
        List<Experience> active = new ArrayList<Experience>();
        for (Experience experience : all()) {
            if (experience.isActive()) {
                active.add(experience);
            }
        }
        return active;
    }

    @Override
    public java.util.Optional<Experience> find(ExperienceId id) {
        Path file = fileOf(id);
        if (!Files.isRegularFile(file)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(codec.read(read(file), ExperienceDto.class).toDomain());
    }

    /** 供淘汰排序用：全部经验（含已归档，避免重复归档时找不到）。 */
    public List<Experience> all() {
        List<Experience> all = new ArrayList<Experience>();
        for (Path file : files()) {
            all.add(codec.read(read(file), ExperienceDto.class).toDomain());
        }
        return all;
    }

    /** 规则出处：§7.1 规则 ⑥（最早且最少被使用先淘汰）。 */
    public Experience evictionCandidate() {
        List<Experience> candidates = all();
        if (candidates.isEmpty()) {
            return null;
        }
        Collections.sort(candidates, new Comparator<Experience>() {
            @Override
            public int compare(Experience a, Experience b) {
                int byCreated = Long.compare(a.createdAt(), b.createdAt());
                return byCreated != 0 ? byCreated : Integer.compare(a.appliedCount(), b.appliedCount());
            }
        });
        return candidates.get(0);
    }

    @Override
    public void save(Experience experience) {
        AtomicFiles.write(fileOf(experience.id()), codec.write(ExperienceDto.from(experience)));
    }

    @Override
    public void archive(ExperienceId id) {
        Experience experience = codec.read(read(fileOf(id)), ExperienceDto.class).toDomain();
        experience.archive();
        save(experience);
    }

    @Override
    public int count() {
        return files().size();
    }

    private Path fileOf(ExperienceId id) {
        return dataDirectory.experience().resolve(id.getValue() + ".json");
    }

    private List<Path> files() {
        List<Path> out = new ArrayList<Path>();
        if (!Files.isDirectory(dataDirectory.experience())) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDirectory.experience(), "*.json")) {
            for (Path path : stream) {
                out.add(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("扫描经验库失败", e);
        }
        return out;
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取经验失败：" + file, e);
        }
    }

    public static class ExperienceDto {

        public String id;
        public String applicableScenario;
        public String instruction;
        public String expectedOutcome;
        public double confidence;
        public String status;
        public int appliedCount;
        public long createdAt;

        static ExperienceDto from(Experience experience) {
            ExperienceDto dto = new ExperienceDto();
            dto.id = experience.id().getValue();
            dto.applicableScenario = experience.applicableScenario();
            dto.instruction = experience.lesson() == null ? null : experience.lesson().getInstruction();
            dto.expectedOutcome = experience.lesson() == null ? null : experience.lesson().getExpectedOutcome();
            dto.confidence = experience.confidence().getValue();
            dto.status = experience.status().name();
            dto.appliedCount = experience.appliedCount();
            dto.createdAt = experience.createdAt();
            return dto;
        }

        Experience toDomain() {
            return new Experience(ExperienceId.of(id), applicableScenario,
                    new LessonLearned(instruction, expectedOutcome), new ConfidenceScore(confidence),
                    ExperienceStatus.valueOf(status), appliedCount, createdAt);
        }
    }
}
