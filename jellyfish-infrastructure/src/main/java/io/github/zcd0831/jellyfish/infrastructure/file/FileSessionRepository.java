package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.perception.session.SessionStatus;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话头与状态；子会话落在 {@code sessions/<root>/sub/<child>/}。
 * 规则出处：模块-基础设施层 §3、§4，技术决策 D05.1。
 */
public final class FileSessionRepository implements SessionRepository {

    private final DataDirectory dir;
    private final JsonCodec codec;

    public FileSessionRepository(DataDirectory dir, JsonCodec codec) {
        this.dir = dir;
        this.codec = codec;
    }

    @Override
    public Session load(SessionId id) {
        Path meta = metaFile(id);
        if (!Files.exists(meta)) {
            return null;
        }
        SessionMetaDto dto = codec.read(read(meta), SessionMetaDto.class);
        return toDomain(dto);
    }

    @Override
    public void save(Session session) {
        SessionMetaDto dto = toDto(session);
        AtomicFiles.write(metaFile(session.id()), codec.write(dto));
    }

    @Override
    public List<SessionId> listResumable() {
        List<SessionId> ids = new ArrayList<>();
        for (Path meta : metaFiles()) {
            SessionMetaDto dto = codec.read(read(meta), SessionMetaDto.class);
            if (dto.status == SessionStatus.RESUMABLE) {
                ids.add(dto.id);
            }
        }
        return ids;
    }

    @Override
    public void archive(SessionId id) {
        Path from = dir.sessionDir(id.getValue());
        Path to = dir.archive().resolve(id.getValue());
        try {
            Files.createDirectories(to.getParent());
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("archive failed: " + id, e);
        }
    }

    @Override
    public void delete(SessionId id) {
        deleteRecursively(dir.sessionDir(id.getValue()));
    }

    private Path metaFile(SessionId id) {
        return dir.sessionDir(id.getValue()).resolve("meta.json");
    }

    private List<Path> metaFiles() {
        List<Path> out = new ArrayList<>();
        Path root = dir.sessions();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path session : stream) {
                Path meta = session.resolve("meta.json");
                if (Files.isRegularFile(meta)) {
                    out.add(meta);
                }
                Path sub = session.resolve("sub");
                if (Files.isDirectory(sub)) {
                    try (DirectoryStream<Path> children = Files.newDirectoryStream(sub)) {
                        for (Path child : children) {
                            Path childMeta = child.resolve("meta.json");
                            if (Files.isRegularFile(childMeta)) {
                                out.add(childMeta);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("scan sessions failed", e);
        }
        return out;
    }

    private static String read(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("read failed: " + path, e);
        }
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try {
            Files.walk(path)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("delete failed: " + path, e);
        }
    }

    private static SessionMetaDto toDto(Session s) {
        SessionMetaDto d = new SessionMetaDto();
        d.id = s.id();
        d.parentId = s.parentSessionId();
        d.agentId = s.agentId();
        d.modelId = s.activeModelId();
        d.status = s.status();
        d.title = s.title();
        d.createdDate = s.createdDate();
        return d;
    }

    private static Session toDomain(SessionMetaDto d) {
        return Session.rehydrate(d.id, d.parentId, d.agentId, d.modelId, d.status, d.title, d.createdDate,
                java.util.Collections.<io.github.zcd0831.jellyfish.domain.perception.session.Record>emptyList(), null);
    }
}
