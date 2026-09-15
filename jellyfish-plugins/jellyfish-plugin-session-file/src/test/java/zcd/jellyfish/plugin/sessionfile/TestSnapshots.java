package zcd.jellyfish.plugin.sessionfile;

import zcd.jellyfish.api.extension.PermissionMode;
import zcd.jellyfish.api.extension.SessionMessageSnapshot;
import zcd.jellyfish.api.extension.SessionSnapshot;
import zcd.jellyfish.api.extension.SessionToolCallSnapshot;
import zcd.jellyfish.api.extension.SessionUsageSnapshot;
import zcd.jellyfish.api.extension.TokenUsageSnapshot;

import java.util.Arrays;
import java.util.List;

/**
 * 测试用的会话快照构造器：把「一个字段都不缺」的快照集中在一处，避免每个用例各写一份。
 *
 * @author zcd
 */
final class TestSnapshots {

    /**
     * 工具类，禁止实例化。
     */
    private TestSnapshots() {
    }

    /**
     * 构造一个字段尽量填满的会话快照。
     *
     * @param sessionId 会话标识
     * @return 会话快照
     */
    static SessionSnapshot full(String sessionId) {
        List<SessionMessageSnapshot> messages = Arrays.asList(
                new SessionMessageSnapshot("m-1", 1L, "user", "你好", null, null, null, null),
                new SessionMessageSnapshot("m-2", 2L, "assistant", "我来读文件", null, null,
                        Arrays.asList(new SessionToolCallSnapshot(0, "call-1", "read_file",
                                "{\"path\":\"a.txt\"}")),
                        new TokenUsageSnapshot(7, 8, 15)),
                new SessionMessageSnapshot("m-3", 3L, "tool", "文件内容", "call-1", "read_file", null, null),
                new SessionMessageSnapshot("m-4", 4L, "assistant", "读完了", null, null, null, null));
        return new SessionSnapshot(sessionId, 100L, 200L, "标题", "coder", "openai", "gpt-4o",
                PermissionMode.PLAN, messages, new SessionUsageSnapshot(7L, 8L, 15L, 4L));
    }

    /**
     * 构造一个只有标识、其余为空的会话快照。
     *
     * @param sessionId 会话标识
     * @return 会话快照
     */
    static SessionSnapshot minimal(String sessionId) {
        return new SessionSnapshot(sessionId, 1L, 1L, null, null, null, null, PermissionMode.NORMAL,
                null, null);
    }
}
