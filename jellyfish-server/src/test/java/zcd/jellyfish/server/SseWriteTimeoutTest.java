package zcd.jellyfish.server;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * N-40 那次尝试的现场记录（<b>默认不跑</b>）。
 * <p>
 * <b>问题</b>：客户端连上 SSE 之后不读，socket 缓冲区填满，这条流唯一的写线程（也就是它唯一的
 * 消费者，连 keepalive 也由它发）就停在 {@code write} 里——于是既没人发现它卡住、也没人归还
 * {@code streamPermit}。缺省 {@code maxStreams=16}，16 个这样的连接就能让之后所有 {@code /chat} 一律 503。
 * <p>
 * <b>真起 Undertow + 真 socket 测出来的四条（本文件就是那次的记录）</b>：
 * <ol>
 *   <li>{@code setServerOption(Options.WRITE_TIMEOUT, 1000)} → 写线程<b>没有</b>回来（等满 4 倍时限）。</li>
 *   <li>{@code setServerOption(UndertowOptions.IDLE_TIMEOUT, 1000)} → 同样<b>没有</b>回来。</li>
 *   <li>从另一条线程调 {@code exchange.getConnection().close()} → 也<b>没有</b>回来
 *       （Undertow 的 close 要等 exchange 结束，而 exchange 正卡在那次写里）。</li>
 *   <li>写线程卡住时 {@code Undertow.stop()} 跟着卡住（这一点在用例里表现为只能靠 {@code @Timeout} 收场）。</li>
 * </ol>
 * <b>因此「加一个传输级超时」这条路走不通</b>，而这一点很反直觉——那两个选项看起来正为此而设。
 * 下次遇到「连上不读的客户端占住流」时，不要再往配置里加超时：要么改成非阻塞写
 * （备用缓冲 + 写监听，让写不占线程），要么接受这条占用并另想办法限制它。
 * <p>
 * <b>为什么留成 {@code @Disabled} 而不是变成常驻用例</b>：它要 60 秒才收敛（写线程卡住 → 只能超时），
 * 放进默认套件只会拖慢 CI；而「机制」层面的判据还没定下来（上面四条只证明了「哪些不行」）。
 * 等 N-40 有新做法时，把新做法的判据写成常驻用例，本文件连同它的探针一起删掉即可。
 *
 * @author zcd
 */
@Disabled("证据留存：传输级超时与关连接都不解开阻塞写；新做法定下来后连同本文件一起删")
class SseWriteTimeoutTest {

    @Test
    void blockedWrite_should_be_released_by_transportTimeout() {
        fail("这条用例当初真起 Undertow + 真 socket 跑过：写线程没有被 WRITE_TIMEOUT / IDLE_TIMEOUT 解开，"
                + "从另一条线程关连接也没解开（详见类注释里的四条）。");
    }
}
