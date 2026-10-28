package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.application.action.port.BudgetRepository;
import io.github.zcd0831.jellyfish.domain.action.budget.Budget;
import io.github.zcd0831.jellyfish.domain.shared.BudgetId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 台账只在会话族根上有；高频小文件，用原子替换。
 * 规则出处：09-infrastructure §9.4。
 */
public final class FileBudgetRepository implements BudgetRepository {

    private final DataDirectory dataDirectory;
    private final SessionDirs sessionDirs;
    private final JsonCodec codec;

    public FileBudgetRepository(DataDirectory dataDirectory, JsonCodec codec) {
        this.dataDirectory = dataDirectory;
        this.sessionDirs = new SessionDirs(dataDirectory);
        this.codec = codec;
    }

    @Override
    public Budget load(SessionId sessionFamilyRootId) {
        Path file;
        try {
            file = file(sessionFamilyRootId);
        } catch (RuntimeException notCreatedYet) {
            // 会话开启时台账先于 meta.json 写，目录可能还不存在
            return null;
        }
        if (!Files.exists(file)) {
            return null;
        }
        try {
            BudgetDto dto = codec.read(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), BudgetDto.class);
            return new Budget(BudgetId.of(dto.id), SessionId.of(dto.sessionFamilyRootId), dto.consumedTokens,
                    dto.warned, dto.lastPromptTokens, dto.lastPromptEstimated, dto.lastPromptSessionId);
        } catch (IOException e) {
            throw new UncheckedIOException("读取台账失败：" + file, e);
        }
    }

    @Override
    public void save(Budget budget) {
        AtomicFiles.write(dataDirectory.sessionDir(budget.sessionFamilyRootId().getValue()).resolve("budget.json"),
                codec.write(toDto(budget)));
    }

    /** 映射只留这一份：`FileCommitScope` 也走它，免得新增字段时漏掉一处。 */
    public static BudgetDto toDto(Budget budget) {
        BudgetDto dto = new BudgetDto();
        dto.id = budget.id().getValue();
        dto.sessionFamilyRootId = budget.sessionFamilyRootId().getValue();
        dto.consumedTokens = budget.consumedTokens();
        dto.warned = budget.warned();
        dto.lastPromptTokens = budget.lastPromptTokens();
        dto.lastPromptEstimated = budget.lastPromptEstimated();
        dto.lastPromptSessionId = budget.lastPromptSessionId();
        return dto;
    }

    private Path file(SessionId rootSessionId) {
        return sessionDirs.of(rootSessionId.getValue()).resolve("budget.json");
    }

    /** 存储形态单独建模，避免聚合字段直接决定 JSON 形状。 */
    public static class BudgetDto {
        public String id;
        public String sessionFamilyRootId;
        public long consumedTokens;
        public boolean warned;
        public long lastPromptTokens;
        public boolean lastPromptEstimated;
        public String lastPromptSessionId;
    }
}
