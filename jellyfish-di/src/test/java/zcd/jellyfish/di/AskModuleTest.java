package zcd.jellyfish.di;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import zcd.jellyfish.api.ask.AskAnswer;
import zcd.jellyfish.api.ask.AskOption;
import zcd.jellyfish.api.ask.AskPort;
import zcd.jellyfish.api.ask.AskRequest;
import zcd.jellyfish.infra.ask.AskChannel;
import zcd.jellyfish.infra.config.AskSettings;
import zcd.jellyfish.infra.config.RuntimeConfig;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AskModule} 的单元测试：钉住「插件拿到的提问端口就是那个真通道」。
 * <p>
 * 这条绑定是插件唯一的出向边，绑错了的后果不会以异常形式出现——插件会安稳地拿到一个
 * 「永远答不上来」的占位，于是 {@code ask_user} 看起来永远问不到人。
 *
 * @author zcd
 */
@DisplayName("AskModule 装配")
class AskModuleTest {

    @Test
    @DisplayName("端口绑的是真通道，而不是能力缺失的占位")
    void provideAskPort_should_bindTheRealChannel() {
        // Given
        AskChannel channel = new AskChannel(config());

        // When
        AskPort port = AskModule.provideAskPort(channel);

        // Then
        assertSame(channel, port);
        assertNotSame(AskPort.unavailable(), port);
        assertInstanceOf(AskChannel.class, port);
    }

    @Test
    @DisplayName("经由端口发起的提问会被真通道收下（未挂答复者时给出「问不到」）")
    void provideAskPort_should_route_through_real_channel() {
        // Given：未 attach，因此通道的行为是「问不到」而不是「拒绝」
        AskPort port = AskModule.provideAskPort(new AskChannel(config()));

        // When
        AskAnswer answer = port.ask(AskRequest.of("ask_user", "s1", "选哪个？",
                Arrays.asList(AskOption.of("a", "甲", null), AskOption.of("b", "乙", null))));

        // Then
        assertEquals(AskAnswer.Status.UNAVAILABLE, answer.getStatus());
        assertTrue(answer.getReason().contains("没有交互界面"), answer.getReason());
    }

    /**
     * 造一份缺省提问配置。
     *
     * @return 运行时配置
     */
    private static RuntimeConfig config() {
        RuntimeConfig runtimeConfig = Mockito.mock(RuntimeConfig.class);
        Mockito.when(runtimeConfig.getAskSettings()).thenReturn(new AskSettings());
        return runtimeConfig;
    }
}
