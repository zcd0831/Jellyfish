package io.github.zcd0831.jellyfish.infrastructure.file;

import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.perception.session.RecordRole;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.perception.session.SkillSummarySnapshot;
import io.github.zcd0831.jellyfish.domain.perception.session.SystemPromptSnapshot;
import io.github.zcd0831.jellyfish.domain.perception.session.ToolResultPayload;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.RecordId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 落盘往返：写进去的每个字段都必须按同一个名字读回来。
 * 规则出处：09-infrastructure §9.5；04-perception §4.4（技能快照三件套）、§4.6（工具结果附属信息）。
 */
class RecordAndSnapshotRoundTripTest {

    @TempDir
    Path tmp;

    private FileRecordStore store() {
        return new FileRecordStore(DataDirectory.of(tmp.toString()), new JsonCodec());
    }

    private static Record truncatedToolResult() {
        return new Record(RecordId.of("r1"), RecordRole.TOOL_RESULT, "半截输出", null, "t1", "m1",
                new ToolResultPayload(true, "artifact://x"), 0, 1, 1739520600000L);
    }

    @Test
    void 工具结果被截断的标记要能读回来() throws Exception {
        FileRecordStore store = store();
        store.appendAll(SessionId.of("s1"), Collections.singletonList(truncatedToolResult()));

        // 磁盘上的名字就是 truncated：读的必须也是它
        String line = new String(Files.readAllBytes(DataDirectory.of(tmp.toString()).sessions()
                .resolve("s1").resolve("records-0001.jsonl")), StandardCharsets.UTF_8);
        assertTrue(line.contains("\"truncated\":true"), line);

        List<Record> reloaded = store.last(SessionId.of("s1"), 1);

        assertTrue(reloaded.get(0).toolPayload().isTruncated(), "截断标记丢了，模型就不知道结果被砍过");
        assertEquals("artifact://x", reloaded.get(0).toolPayload().getResourceUri());
    }

    @Test
    void 工具结果没被截断也读回false() {
        FileRecordStore store = store();
        store.appendAll(SessionId.of("s1"), Collections.singletonList(new Record(RecordId.of("r2"),
                RecordRole.TOOL_RESULT, "完整", null, "t1", "m1",
                new ToolResultPayload(false, null), 0, 1, 1739520600000L)));

        assertTrue(!store.last(SessionId.of("s1"), 1).get(0).toolPayload().isTruncated());
    }

    @Test
    void 技能快照的触发条件要能读回来() {
        FileSessionRepository repo = new FileSessionRepository(DataDirectory.of(tmp.toString()), new JsonCodec());
        Session session = Session.rehydrate(SessionId.of("s1"), null, AgentId.of("builtin.main"),
                ModelId.of("m1"), "标题", 1739520600000L, Collections.<Record>emptyList(),
                new SystemPromptSnapshot("渲染后的提示词", Collections.emptyList(),
                        Collections.singletonList(
                                new SkillSummarySnapshot("code-review", "代码评审", "当需要评审时"))));

        repo.save(session);
        SkillSummarySnapshot reloaded = repo.load(SessionId.of("s1")).systemPromptSnapshot().getSkillList().get(0);

        assertEquals("当需要评审时", reloaded.getTriggerCondition(),
                "触发条件是技能浅层三件套之一，冻结后必须读得回来");
    }

    @Test
    void 老meta文件没有触发条件也能读() throws Exception {
        Path meta = DataDirectory.of(tmp.toString()).sessions().resolve("s9").resolve("meta.json");
        Files.createDirectories(meta.getParent());
        Files.write(meta, ("{\"id\":\"s9\",\"agentId\":\"builtin.main\",\"modelId\":\"m1\",\"title\":\"旧\","
                + "\"createdDate\":1,\"systemPrompt\":{\"renderedPrompt\":\"p\",\"skills\":"
                + "[{\"name\":\"n\",\"description\":\"d\"}]}}").getBytes(StandardCharsets.UTF_8));

        SkillSummarySnapshot reloaded = new FileSessionRepository(DataDirectory.of(tmp.toString()), new JsonCodec())
                .load(SessionId.of("s9")).systemPromptSnapshot().getSkillList().get(0);

        assertNull(reloaded.getTriggerCondition(), "老文件没记触发条件就是 null，不报错");
    }
}
