package zcd.jellyfish.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JellyfishServer} 的暴露面判据。
 * <p>
 * 只钉「拒绝启动」这一条：它是「无密钥 + 非回环」的唯一防线，而唯一的提示原先只是一行 WARN 日志——
 * 那是最容易被忽略、又最不该被忽略的一种配置。
 *
 * @author zcd
 */
@DisplayName("JellyfishServer 暴露面")
class JellyfishServerTest {

    @Test
    @DisplayName("绑非回环却不配 API key：拒绝启动，而不是只 WARN 一句就敞开")
    void requireUsableExposure_should_reject_when_nonLoopbackWithoutKey() {
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> JellyfishServer.requireUsableExposure(config("0.0.0.0", null)));

        assertTrue(error.getMessage().contains("未配 API key"), error.getMessage());
    }

    @Test
    @DisplayName("回环不配 key 放行：这是刻意的缺省，只服务「本机跑一次」")
    void requireUsableExposure_should_allow_when_loopbackWithoutKey() {
        assertDoesNotThrow(() -> JellyfishServer.requireUsableExposure(config("127.0.0.1", null)));
        // 判据用 InetAddress 而不是字符串比对：localhost / ::1 / 127.0.0.2 都是回环
        assertDoesNotThrow(() -> JellyfishServer.requireUsableExposure(config("localhost", null)));
        assertDoesNotThrow(() -> JellyfishServer.requireUsableExposure(config("::1", null)));
        assertDoesNotThrow(() -> JellyfishServer.requireUsableExposure(config("127.0.0.2", null)));
    }

    @Test
    @DisplayName("配了 key 就可以对外：风险由密钥接管")
    void requireUsableExposure_should_allow_when_keyGiven() {
        assertDoesNotThrow(() -> JellyfishServer.requireUsableExposure(config("0.0.0.0", "s3cret-key")));
    }

    @Test
    @DisplayName("监听地址解析不出来时按非回环处理：多半是主机名写错了，让它在启动期暴露")
    void requireUsableExposure_should_reject_when_hostUnresolvable() {
        assertThrows(JellyfishException.class,
                () -> JellyfishServer.requireUsableExposure(config("no-such-host.invalid", null)));
    }

    /**
     * 构造运行参数。
     *
     * @param host   监听地址
     * @param apiKey API key，可为 {@code null}
     * @return 运行参数
     */
    private static ServerConfig config(String host, String apiKey) {
        return ServerConfig.builder(host, 9096).apiKey(apiKey).build();
    }
}
