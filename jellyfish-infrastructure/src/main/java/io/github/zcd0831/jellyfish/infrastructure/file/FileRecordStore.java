package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordDraft;
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

    /**
     * 只读尾部：从最后一个分段文件倒着读，凑够 count 条完整行就停。
     * 启动对账每个会话都要取末尾两条，读全量会让启动成本正比于历史流水总量
     * （100 会话 × 1000 条实测 841ms，几乎全花在这里）。
     * 规则出处：09-infrastructure §9.5、§9.7（末尾半行跳过）。
     */
    @Override
    public List<Record> last(SessionId sessionId, int count) {
        if (count <= 0) {
            return new ArrayList<Record>();
        }
        Path sessionDir = dir.sessionDir(sessionId.getValue());
        List<Path> all = segments(sessionDir);
        List<String> lines = new ArrayList<String>();
        for (int i = all.size() - 1; i >= 0 && lines.size() < count; i--) {
            try {
                lines.addAll(0, tailCompleteLines(all.get(i), count - lines.size()));
            } catch (IOException e) {
                throw new UncheckedIOException("read failed: " + all.get(i), e);
            }
        }
        List<Record> out = new ArrayList<Record>(lines.size());
        for (String line : lines) {
            out.add(toDomain(codec.read(line, RecordLineDto.class)));
        }
        return out;
    }

    /**
     * 从文件尾部倒着读，返回末尾至多 {@code needed} 条完整行（顺序保持正序）。
     * 末行没有换行 ⇒ 崩溃写了一半，丢弃（与 {@link #completeLines} 同一口径）。
     */
    private static List<String> tailCompleteLines(Path segment, int needed) throws IOException {
        long length = Files.size(segment);
        if (length == 0) {
            return new ArrayList<String>();
        }
        byte[] acc = new byte[0];
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(segment.toFile(), "r")) {
            long position = length;
            while (position > 0) {
                int size = (int) Math.min(8192, position);
                position -= size;
                byte[] chunk = new byte[size];
                file.seek(position);
                file.readFully(chunk);
                byte[] merged = new byte[size + acc.length];
                System.arraycopy(chunk, 0, merged, 0, size);
                System.arraycopy(acc, 0, merged, size, acc.length);
                acc = merged;
                if (completeLineCount(acc) > needed) {
                    break;   // 多要一条：末行可能没有换行，是半行
                }
            }
        }
        List<String> lines = new ArrayList<String>();
        for (String line : splitCompleteLines(acc)) {
            lines.add(line);
        }
        int from = Math.max(0, lines.size() - needed);
        return new ArrayList<String>(lines.subList(from, lines.size()));
    }

    private static int completeLineCount(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    private static List<String> splitCompleteLines(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<String>();
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
        return lines;   // start < 长度 ⇒ 末尾半行，丢弃
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

    private static RecordLineDto.ToolPayloadDto payloadOf(
            io.github.zcd0831.jellyfish.domain.perception.session.ToolResultPayload payload) {
        if (payload == null) {
            return null;
        }
        RecordLineDto.ToolPayloadDto dto = new RecordLineDto.ToolPayloadDto();
        dto.truncated = payload.isTruncated();
        dto.resourceUri = payload.getResourceUri();
        return dto;
    }

    private static RecordLineDto toDto(Record r) {
        RecordLineDto d = new RecordLineDto();
        d.id = r.id();
        d.role = r.role();
        d.content = r.content();
        d.reasoning = r.reasoning();
        d.turnId = r.turnId();
        d.producerModelId = r.producerModelId();
        d.toolPayload = payloadOf(r.toolPayload());
        d.summaryCoversUpTo = r.summaryCoversUpTo();
        d.sequence = r.sequence();
        d.date = r.date();
        return d;
    }

    private static Record toDomain(RecordLineDto d) {
        return Record.restore(d.id, RecordDraft.builder(d.role, d.content)
                .reasoning(d.reasoning).turnId(d.turnId).producerModelId(d.producerModelId)
                .toolPayload(payloadToDomain(d.toolPayload)).summaryCoversUpTo(d.summaryCoversUpTo)
                .build(), d.sequence, d.date);
    }

    private static io.github.zcd0831.jellyfish.domain.perception.session.ToolResultPayload payloadToDomain(
            RecordLineDto.ToolPayloadDto dto) {
        if (dto == null) {
            return null;
        }
        return new io.github.zcd0831.jellyfish.domain.perception.session.ToolResultPayload(
                dto.truncated, dto.resourceUri);
    }
}
