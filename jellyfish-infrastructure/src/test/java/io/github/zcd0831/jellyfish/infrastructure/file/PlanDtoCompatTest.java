package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.domain.planning.plan.Attempt;
import io.github.zcd0831.jellyfish.domain.planning.plan.AttemptOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 旧 plan.json 里 attempt 还带 startRecordId / endRecordId（模型已删 RecordRange）：
 * 反序列化按「未知字段忽略」处理，不报错、不迁移。
 * 规则出处：06-planning §6.6（Attempt 不记录对话区间）、09-infrastructure §9.4。
 */
class PlanDtoCompatTest {

    private final JsonCodec codec = new JsonCodec();

    @Test
    void 旧planJson带记录区间也能读() {
        String legacy = "{\"attemptNo\":2,\"startRecordId\":\"r1\",\"endRecordId\":\"r4\","
                + "\"verificationReportId\":\"v1\",\"outcome\":\"SUCCEEDED\"}";

        Attempt attempt = codec.read(legacy, PlanDto.AttemptDto.class).toDomain();

        assertEquals(2, attempt.attemptNo());
        assertEquals("v1", attempt.verificationReportId().getValue());
        assertEquals(AttemptOutcome.SUCCEEDED, attempt.outcome());
    }
}
