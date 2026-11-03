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
    public void appendAll(SessionId sessionId, List<Record> records) {
        if (records.isEmpty()) {
            return;
        }
        Path sessionDir = writableSessionDir(sessionId);
        Path segment = currentSegment(sessionDir);
        List<String> lines = new ArrayList<String>(records.size());
        for (Record record : records) {
            lines.add(codec.write(toDto(record)));
        }
        AtomicFiles.appendAll(segment, lines);
        // 阶段末尾让目录项落盘：换段时新文件才真的存在（09-infrastructure §9.6「流水按段滚动」）
                AtomicFiles.syncDirectory(sessionDir);
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

    /** 写入用的目录：会话目录可能尚未建立（首次提交即建），此时返回将要用到的路径。 */
    private Path writableSessionDir(SessionId sessionId) {
        Path direct = dir.sessionDir(sessionId.getValue());
        if (Files.isDirectory(direct)) {
            return direct;
        }
        if (!Files.isDirectory(dir.sessions())) {
            return direct;   // 连 sessions/ 都还没有：首次写入，直接用目标路径
        }
        try {
            return sessionDir(sessionId);   // 可能是已存在的子会话
        } catch (IllegalArgumentException notYet) {
            return direct;                  // 新会话：目录由写入时创建
        }
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
                for (String line : completeLines(segment)) {
                    out.add(toDomain(codec.read(line, RecordLineDto.class)));
                }
            } catch (IOException e) {
                throw new UncheckedIOException("read failed: " + segment, e);
            }
        }
        return out;
    }

    /**
     * 只取以换行结尾的完整行。末尾没有换行的那一段是崩溃写了一半的记录，一律跳过。
     * 规则出处：09-infrastructure §9.7「读取时遇到末尾半行一律跳过，不视为错误」。
     */
    private static List<String> completeLines(Path segment) throws IOException {
        String text = new String(Files.readAllBytes(segment), StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '\n') {
                continue;
            }
            String line = text.substring(start, i);
            if (!line.trim().isEmpty()) {
                lines.add(line);
            }
            start = i + 1;
        }
        // start < text.length() ⇒ 末尾无换行，属于半行，丢弃
        return lines;
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
