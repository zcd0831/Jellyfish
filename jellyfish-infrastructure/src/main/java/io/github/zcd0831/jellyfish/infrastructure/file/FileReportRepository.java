package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.application.planning.port.VerificationReportRepository;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationOutcome;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationReport;
import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationType;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import io.github.zcd0831.jellyfish.domain.shared.VerificationReportId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * reports/<reportId>.json：**只新建、不覆盖**；需要知道 plan/session 才能定位。
 * 规则出处：09-infrastructure §9.4（只新建）。
 */
public final class FileReportRepository implements VerificationReportRepository {

    private final DataDirectory dataDirectory;
    private final SessionDirs sessionDirs;
    private final JsonCodec codec;
    private final PlanOwnerIndex owners;

    public FileReportRepository(DataDirectory dataDirectory, JsonCodec codec) {
        this.dataDirectory = dataDirectory;
        this.sessionDirs = new SessionDirs(dataDirectory);
        this.codec = codec;
        this.owners = new PlanOwnerIndex(dataDirectory);
    }

    @Override
    public void save(VerificationReport report) {
        Path dir = reportsDir(report.planId());
        if (dir == null) {
            throw new IllegalStateException("找不到计划所属会话，无法落报告：" + report.planId().getValue());
        }
        Path file = dir.resolve(report.id().getValue() + ".json");
        if (Files.exists(file)) {
            // 报告不可变：已存在即视为重复提交，直接跳过（幂等）
            return;
        }
        AtomicFiles.write(file, codec.write(ReportDto.from(report)));
    }

    @Override
    public Optional<VerificationReport> get(VerificationReportId id) {
        for (Path dir : allReportDirs()) {
            Path file = dir.resolve(id.getValue() + ".json");
            if (Files.isRegularFile(file)) {
                return Optional.of(codec.read(read(file), ReportDto.class).toDomain());
            }
        }
        return Optional.empty();
    }

    @Override
    public List<VerificationReport> findByStep(PlanId planId, StepId stepId) {
        List<VerificationReport> out = new ArrayList<VerificationReport>();
        Path dir = reportsDir(planId);
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                VerificationReport report = codec.read(read(file), ReportDto.class).toDomain();
                if (report.stepId().equals(stepId)) {
                    out.add(report);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取报告失败：" + dir, e);
        }
        return out;
    }

    private Path reportsDir(PlanId planId) {
        String sessionId = owners.sessionOf(planId);
        return sessionId == null ? null : sessionDirs.of(sessionId).resolve("reports");
    }

    private List<Path> allReportDirs() {
        List<Path> dirs = new ArrayList<Path>();
        if (!Files.isDirectory(dataDirectory.sessions())) {
            return dirs;
        }
        try (DirectoryStream<Path> sessions = Files.newDirectoryStream(dataDirectory.sessions())) {
            for (Path session : sessions) {
                dirs.add(session.resolve("reports"));
                Path sub = session.resolve("sub");
                if (Files.isDirectory(sub)) {
                    try (DirectoryStream<Path> subSessions = Files.newDirectoryStream(sub)) {
                        for (Path subSession : subSessions) {
                            dirs.add(subSession.resolve("reports"));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("扫描报告目录失败", e);
        }
        return dirs;
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取报告失败：" + file, e);
        }
    }

    public static class ReportDto {

        public String id;
        public String planId;
        public String stepId;
        public int attemptNo;
        public String outcome;
        public String failedType;
        public String failureReason;
        public long decidedAt;

        static ReportDto from(VerificationReport report) {
            ReportDto dto = new ReportDto();
            dto.id = report.id().getValue();
            dto.planId = report.planId().getValue();
            dto.stepId = report.stepId().getValue();
            dto.attemptNo = report.attemptNo();
            dto.outcome = report.outcome().name();
            dto.failedType = report.failedType() == null ? null : report.failedType().name();
            dto.failureReason = report.failureReason();
            dto.decidedAt = report.decidedAt();
            return dto;
        }

        VerificationReport toDomain() {
            return VerificationReport.decide(VerificationReportId.of(id), PlanId.of(planId), StepId.of(stepId),
                    attemptNo, VerificationOutcome.valueOf(outcome),
                    failedType == null ? null : VerificationType.valueOf(failedType), failureReason, decidedAt);
        }
    }
}
