package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.domain.planning.plan.Plan;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanStatus;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * sessions/<id>/plans/<planId>.json；`findRunning` 扫目录（对账用）。
 * 规则出处：09-infrastructure §9.4、§9.5。
 */
public final class FilePlanRepository implements PlanRepository {

    private final DataDirectory dataDirectory;
    private final SessionDirs sessionDirs;
    private final JsonCodec codec;

    public FilePlanRepository(DataDirectory dataDirectory, JsonCodec codec) {
        this.dataDirectory = dataDirectory;
        this.sessionDirs = new SessionDirs(dataDirectory);
        this.codec = codec;
    }

    @Override
    public Plan load(PlanId id) {
        Path file = locate(id);
        if (file == null) {
            return null;
        }
        return codec.read(read(file), PlanDto.class).toDomain();
    }

    @Override
    public void save(Plan plan) {
        Path file = plansDir(plan.sessionId()).resolve(plan.id().getValue() + ".json");
        AtomicFiles.write(file, codec.write(PlanDto.from(plan)));
    }

    @Override
    public List<Plan> findRunning(SessionId sessionId) {
        List<Plan> out = new ArrayList<Plan>();
        Path dir = plansDir(sessionId);
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                Plan plan = codec.read(read(file), PlanDto.class).toDomain();
                if (plan.status() == PlanStatus.EXECUTING || plan.status() == PlanStatus.REPLANNING) {
                    out.add(plan);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("扫描计划目录失败：" + dir, e);
        }
        return out;
    }

    private Path locate(PlanId id) {
        if (!Files.isDirectory(dataDirectory.sessions())) {
            return null;
        }
        try (DirectoryStream<Path> sessions = Files.newDirectoryStream(dataDirectory.sessions())) {
            for (Path session : sessions) {
                Path candidate = session.resolve("plans").resolve(id.getValue() + ".json");
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
                Path child = session.resolve("sub");
                if (Files.isDirectory(child)) {
                    try (DirectoryStream<Path> subSessions = Files.newDirectoryStream(child)) {
                        for (Path sub : subSessions) {
                            Path subCandidate = sub.resolve("plans").resolve(id.getValue() + ".json");
                            if (Files.isRegularFile(subCandidate)) {
                                return subCandidate;
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("定位计划失败：" + id.getValue(), e);
        }
        return null;
    }

    private Path plansDir(SessionId sessionId) {
        return sessionDirs.of(sessionId.getValue()).resolve("plans");
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取计划失败：" + file, e);
        }
    }
}
