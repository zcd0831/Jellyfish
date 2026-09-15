package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link PanelContributionRequest} 的单元测试。
 *
 * @author zcd
 */
@DisplayName("面板贡献请求")
class PanelContributionRequestTest {

    @Test
    @DisplayName("是类型级请求：路由键恒为 null，因此走 contribute 而不是 handle")
    void getRouteKey_should_beNull() {
        assertNull(new PanelContributionRequest("s-1").getRouteKey());
    }

    @Test
    @DisplayName("结果类型声明为面板贡献，供注册表做运行时校验")
    void getResultType_should_beContribution() {
        assertEquals(PanelContribution.class, new PanelContributionRequest("s-1").getResultType());
    }

    @Test
    @DisplayName("会话标识原样透传：插件据此找回自己那份状态")
    void getSessionId_should_returnConstructorValue() {
        assertEquals("s-1", new PanelContributionRequest("s-1").getSessionId());
    }

    @Test
    @DisplayName("允许没有会话上下文")
    void getSessionId_should_allowNull() {
        assertNull(new PanelContributionRequest(null).getSessionId());
    }
}
