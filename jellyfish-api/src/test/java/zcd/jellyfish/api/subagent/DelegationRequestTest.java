package zcd.jellyfish.api.subagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CancellationToken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link DelegationRequest} 的单元测试。
 *
 * @author zcd
 */
@DisplayName("委派请求")
class DelegationRequestTest {

    @Test
    @DisplayName("取消令牌缺省为 NONE：插件不必为「没有令牌」写分支")
    void constructor_should_defaultCancellationToken() {
        DelegationRequest request = new DelegationRequest("s-1", "scout", "查一下", null);
        assertSame(CancellationToken.NONE, request.getCancellationToken());
    }

    @Test
    @DisplayName("任务原文可以为空：是否值得拒绝由内核判定，不由 api 猜")
    void constructor_shouldKeepNullPrompt() {
        assertNull(new DelegationRequest("s-1", "scout", null, null).getPrompt());
    }

    @Test
    @DisplayName("父会话标识与 agent 标识不得为空白")
    void constructor_shouldRejectBlankIds() {
        assertThrows(JellyfishException.class,
                () -> new DelegationRequest("  ", "scout", "查一下", null));
        assertThrows(JellyfishException.class,
                () -> new DelegationRequest("s-1", null, "查一下", null));
    }

    @Test
    @DisplayName("静态工厂给出不可取消的请求")
    void of_shouldBehaveLikeConstructorWithoutToken() {
        DelegationRequest request = DelegationRequest.of("s-1", "scout", "查一下");
        assertEquals("s-1", request.getParentSessionId());
        assertEquals("scout", request.getAgentId());
        assertEquals("查一下", request.getPrompt());
        assertSame(CancellationToken.NONE, request.getCancellationToken());
    }
}
