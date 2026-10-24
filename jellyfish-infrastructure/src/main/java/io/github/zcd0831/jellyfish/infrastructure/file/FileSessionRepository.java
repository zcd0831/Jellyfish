package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.application.perception.port.SessionSummary;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 会话头与状态；子会话落在 {@code sessions/<root>/sub/<child>/}。
 * 规则出处：模块-基础设施层 §3、§4，技术决策 D05.1。
 */
public final class FileSessionRepository implements SessionRepository {

    private final DataDirectory dir;
    private final SessionDirs sessionDirs;
    private final JsonCodec codec;

    public FileSessionRepository(DataDirectory dir, JsonCodec codec) {
        this.dir = dir;
        this.sessionDirs = new SessionDirs(dir);
        this.codec = codec;
    }

    @Override
    public Session load(SessionId id) {
        Path meta;
        try {
            meta = sessionDirs.of(id.getValue()).resolve("meta.json");
        } catch (RuntimeException notFound) {
            return null;
        }
        if (!Files.exists(meta)) {
            return null;
        }
        SessionMetaDto dto = codec.read(read(meta), SessionMetaDto.class);
        return SessionMetaMapper.toDomain(dto, java.util.Collections.<io.github.zcd0831.jellyfish.domain.perception.session.Record>emptyList());
    }

    @Override
    public void save(Session session) {
        SessionMetaDto dto = SessionMetaMapper.toDto(session);
        AtomicFiles.write(metaFile(session), codec.write(dto));
    }

    /** 子会话落在主会话的 sub/ 下，深度恒为 1（D05.1）。 */
    private Path metaFile(Session session) {
        if (session.parentSessionId() == null) {
            return dir.sessionDir(session.id().getValue()).resolve("meta.json");
        }
        Path parentDir = sessionDirs.of(session.parentSessionId().getValue());
        return parentDir.resolve("sub").resolve(session.id().getValue()).resolve("meta.json");
    }

    @Override
    public List<SessionSummary> list() {
        List<SessionSummary> out = new ArrayList<>();
        for (Path meta : metaFiles()) {
            SessionMetaDto dto = codec.read(read(meta), SessionMetaDto.class);
            out.add(new SessionSummary(dto.id, dto.parentId, dto.title, dto.modelId, dto.createdDate));
        }
        out.sort(new Comparator<SessionSummary>() {
            @Override
            public int compare(SessionSummary left, SessionSummary right) {
                return Long.compare(right.createdDate(), left.createdDate());
            }
        });
        return out;
    }

    /**
     * 归档 = 压成 {@code archive/<id>.zip} 再删原目录（D22）：先写 `.tmp`、改名、最后删，
     * 顺序保证"包没写完就不会丢数据"，翻车时由 {@link #cleanupArchiveResidue()} 收尾。
     */
    @Override
    public void archive(SessionId id) {
        Path from = dir.sessionDir(id.getValue());
        if (!Files.isDirectory(from)) {
            throw new UncheckedIOException("archive failed: 会话不存在 " + id,
                    new java.io.IOException("missing " + from));
        }
        Path target = dir.archive().resolve(id.getValue() + ".zip");
        Path tmp = dir.archive().resolve(id.getValue() + ".zip.tmp");
        try {
            Files.createDirectories(dir.archive());
            Files.deleteIfExists(tmp);
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp))) {
                addDirectory(zip, from, "");
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            deleteRecursively(from);
        } catch (IOException e) {
            throw new UncheckedIOException("archive failed: " + id, e);
        }
    }

    /** 归档中间态对账：半截包丢弃；包已完整就补删原目录（幂等）。 */
    @Override
    public void cleanupArchiveResidue() {
        Path archive = dir.archive();
        if (!Files.isDirectory(archive)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(archive)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.endsWith(".zip.tmp")) {
                    Files.deleteIfExists(entry);
                } else if (name.endsWith(".zip")) {
                    Path live = dir.sessionDir(name.substring(0, name.length() - ".zip".length()));
                    if (Files.isDirectory(live)) {
                        deleteRecursively(live);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("归档中间态对账失败", e);
        }
    }

    private void addDirectory(ZipOutputStream zip, Path directory, String prefix) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                String name = prefix + entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    addDirectory(zip, entry, name + "/");
                    continue;
                }
                zip.putNextEntry(new ZipEntry(name));
                try (InputStream in = Files.newInputStream(entry)) {
                    copy(in, zip);
                }
                zip.closeEntry();
            }
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
    }

    @Override
    public void delete(SessionId id) {
        deleteRecursively(dir.sessionDir(id.getValue()));
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

}
