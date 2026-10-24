package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.application.memory.port.ReflectionRepository;
import io.github.zcd0831.jellyfish.domain.memory.reflection.Reflection;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.ReflectionId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * sessions/<id>/reflections/<id>.json：只新建，不覆盖（反思不可变）。
 * 规则出处：模块-基础设施层 §3、模块-记忆 §7.2 规则 ④。
 */
public final class FileReflectionRepository implements ReflectionRepository {

    private final DataDirectory dataDirectory;
    private final SessionDirs sessionDirs;
    private final JsonCodec codec;

    public FileReflectionRepository(DataDirectory dataDirectory, JsonCodec codec) {
        this.dataDirectory = dataDirectory;
        this.sessionDirs = new SessionDirs(dataDirectory);
        this.codec = codec;
    }

    @Override
    public void save(Reflection reflection) {
        Path file = fileOf(reflection);
        if (Files.exists(file)) {
            return;   // 归属唯一：同一个 (plan, step, attempt) 只有一份
        }
        AtomicFiles.write(file, codec.write(ReflectionDto.from(reflection)));
    }

    @Override
    public Optional<Reflection> get(ReflectionId id) {
        for (Path dir : reflectionDirs()) {
            Path file = dir.resolve(id.getValue() + ".json");
            if (Files.isRegularFile(file)) {
                return Optional.of(codec.read(read(file), ReflectionDto.class).toDomain());
            }
        }
        return Optional.empty();
    }

    private Path fileOf(Reflection reflection) {
        return sessionDirOf(reflection).resolve("reflections")
                .resolve(reflection.id().getValue() + ".json");
    }

    private Path sessionDirOf(Reflection reflection) {
        String sessionId = new PlanOwnerIndex(dataDirectory).sessionOf(reflection.planId());
        if (sessionId == null) {
            throw new IllegalStateException("找不到计划所属会话，无法落反思：" + reflection.planId().getValue());
        }
        return sessionDirs.of(sessionId);
    }

    private Iterable<Path> reflectionDirs() {
        java.util.List<Path> dirs = new java.util.ArrayList<Path>();
        if (!Files.isDirectory(dataDirectory.sessions())) {
            return dirs;
        }
        try (DirectoryStream<Path> sessions = Files.newDirectoryStream(dataDirectory.sessions())) {
            for (Path session : sessions) {
                dirs.add(session.resolve("reflections"));
                Path sub = session.resolve("sub");
                if (Files.isDirectory(sub)) {
                    try (DirectoryStream<Path> subSessions = Files.newDirectoryStream(sub)) {
                        for (Path subSession : subSessions) {
                            dirs.add(subSession.resolve("reflections"));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("扫描反思目录失败", e);
        }
        return dirs;
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取反思失败：" + file, e);
        }
    }

    public static class ReflectionDto {

        public String id;
        public String planId;
        public String stepId;
        public int attemptNo;
        public String conclusion;
        public long createdAt;

        static ReflectionDto from(Reflection reflection) {
            ReflectionDto dto = new ReflectionDto();
            dto.id = reflection.id().getValue();
            dto.planId = reflection.planId().getValue();
            dto.stepId = reflection.stepId().getValue();
            dto.attemptNo = reflection.attemptNo();
            dto.conclusion = reflection.conclusion();
            dto.createdAt = reflection.createdAt();
            return dto;
        }

        Reflection toDomain() {
            return Reflection.rehydrate(ReflectionId.of(id), PlanId.of(planId), StepId.of(stepId), attemptNo,
                    conclusion, createdAt);
        }
    }
}
