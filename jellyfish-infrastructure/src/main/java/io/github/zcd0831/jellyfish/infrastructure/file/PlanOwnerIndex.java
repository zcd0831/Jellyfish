package io.github.zcd0831.jellyfish.infrastructure.file;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * planId → sessionId：报告落盘需要知道计划属于哪个会话，而报告里只存 planId。
 * 直接扫 plans/ 目录（深度 ≤ 2，D05.1），不额外维护索引文件。
 */
final class PlanOwnerIndex {

    private final DataDirectory dataDirectory;

    PlanOwnerIndex(DataDirectory dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    String sessionOf(io.github.zcd0831.jellyfish.domain.shared.PlanId planId) {
        for (Path plansDir : allPlanDirs()) {
            if (Files.isRegularFile(plansDir.resolve(planId.getValue() + ".json"))) {
                return plansDir.getParent().getFileName().toString();
            }
        }
        return null;
    }

    private Iterable<Path> allPlanDirs() {
        java.util.List<Path> dirs = new java.util.ArrayList<Path>();
        if (!Files.isDirectory(dataDirectory.sessions())) {
            return dirs;
        }
        try (DirectoryStream<Path> sessions = Files.newDirectoryStream(dataDirectory.sessions())) {
            for (Path session : sessions) {
                dirs.add(session.resolve("plans"));
                Path sub = session.resolve("sub");
                if (Files.isDirectory(sub)) {
                    try (DirectoryStream<Path> subSessions = Files.newDirectoryStream(sub)) {
                        for (Path subSession : subSessions) {
                            dirs.add(subSession.resolve("plans"));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("扫描计划目录失败", e);
        }
        return dirs;
    }
}
