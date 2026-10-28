package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.shared.RecordId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 流水：一行一条 JSON，超过 32 MiB 滚动到下一个分段；区间基于 seq。
 * 规则出处：09-infrastructure §9.5、§9.6。
 */
public final class FileRecordStore implements RecordStore {

    private static final long SEGMENT_LIMIT_BYTES = 32L * 1024 * 1024;

    private final DataDirectory dir;
    private final JsonCodec codec;

    public FileRecordStore(DataDirectory dir, JsonCodec codec) {
        this.dir = dir;
        this.codec = codec;
    }

    @Override
    public void append(SessionId sessionId, Record record) {
        Path segment = currentSegment(sessionDir(sessionId));
        AtomicFiles.append(segment, codec.write(toDto(record)));
    }

    @Override
    public List<Record> last(SessionId sessionId, int count) {
        List<Record> all = readAll(sessionDir(sessionId));
        int from = Math.max(0, all.size() - count);
        return all.subList(from, all.size());
    }

    @Override
    public List<Record> range(SessionId sessionId, RecordId start, RecordId end) {
        List<Record> all = readAll(sessionDir(sessionId));
        int from = indexOf(all, start);
        int to = indexOf(all, end);
        return all.subList(from, to + 1);
    }

    private static int indexOf(List<Record> records, RecordId id) {
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).id().equals(id)) {
                return i;
            }
        }
        throw new IllegalArgumentException("record not found: " + id);
    }

    private Path sessionDir(SessionId sessionId) {
        Path root = dir.sessions();
        Path direct = root.resolve(sessionId.getValue());
        if (Files.isDirectory(direct)) {
            return direct;
        }
        // 子会话：沿 sessions/*/sub/<id> 找
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path session : stream) {
                Path child = session.resolve("sub").resolve(sessionId.getValue());
                if (Files.isDirectory(child)) {
                    return child;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("locate session failed: " + sessionId, e);
        }
        throw new IllegalArgumentException("session dir not found: " + sessionId);
    }

    private Path currentSegment(Path sessionDir) {
        List<Path> segments = segments(sessionDir);
        if (segments.isEmpty()) {
            return sessionDir.resolve("records-0001.jsonl");
        }
        Path last = segments.get(segments.size() - 1);
        try {
            return Files.size(last) >= SEGMENT_LIMIT_BYTES
                    ? sessionDir.resolve(String.format("records-%04d.jsonl", segments.size() + 1))
                    : last;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> segments(Path sessionDir) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(sessionDir)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sessionDir, "records-*.jsonl")) {
            for (Path p : stream) {
                out.add(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(Comparator.comparing(Path::getFileName));
        return out;
    }

    private List<Record> readAll(Path sessionDir) {
        List<Record> out = new ArrayList<>();
        for (Path segment : segments(sessionDir)) {
            try {
                for (String line : Files.readAllLines(segment, StandardCharsets.UTF_8)) {
                    if (!line.trim().isEmpty()) {
                        out.add(toDomain(codec.read(line, RecordLineDto.class)));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("read failed: " + segment, e);
            }
        }
        return out;
    }

    private static RecordLineDto toDto(Record r) {
        RecordLineDto d = new RecordLineDto();
        d.id = r.id();
        d.role = r.role();
        d.content = r.content();
        d.reasoning = r.reasoning();
        d.turnId = r.turnId();
        d.producerModelId = r.producerModelId();
        d.toolPayload = r.toolPayload();
        d.summaryCoversUpTo = r.summaryCoversUpTo();
        d.sequence = r.sequence();
        d.date = r.date();
        return d;
    }

    private static Record toDomain(RecordLineDto d) {
        return new Record(d.id, d.role, d.content, d.reasoning, d.turnId, d.producerModelId, d.toolPayload,
                d.summaryCoversUpTo, d.sequence, d.date);
    }
}
