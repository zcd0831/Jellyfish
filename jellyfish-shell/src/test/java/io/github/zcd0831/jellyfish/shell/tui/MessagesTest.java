package io.github.zcd0831.jellyfish.shell.tui;

import io.github.zcd0831.jellyfish.domain.perception.session.SessionError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * code → 人话：缺 key 会静默回退成 code 本身，用户就只能看到一串错误码。
 * 所以每个会话类错误码都必须有文案（S_RUN_IN_PROGRESS 就是这么漏过一次的）。
 */
class MessagesTest {

    @Test
    void 会话类错误码都有文案() {
        for (SessionError error : SessionError.values()) {
            assertNotEquals(error.code(), Messages.text(error.code()), "缺少文案：" + error.code());
        }
    }

    @Test
    void 运行中被拒的文案说得清怎么办() {
        assertTrue(Messages.text(SessionError.RUN_IN_PROGRESS.code()).contains("正在运行"),
                Messages.text(SessionError.RUN_IN_PROGRESS.code()));
    }
}
