package zcd.jellyfish.server;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 与 {@link SseWriteTimeoutTest} 同一次的探针（<b>默认不跑，重复文件，待删</b>）。
 * <p>
 * 两条文件是同一个探针的两次迭代：先写在 {@code SseWriteTimeoutTest}，为了把「客户端不读」与
 * 「客户端真的断开」分开对照又拆了一份。后来发现写线程卡住时连 {@code Undertow.stop()} 都会跟着卡，
 * 这类用例只能靠 {@code @Timeout} 收场（60 秒），不适合进默认套件，因此两份都留成
 * {@code @Disabled} 的现场记录。
 * <p>
 * <b>这一份没跑完</b>：它试的是「客户端真的断开能不能解开写」，而那一轮的断言结果被 60 秒超时遮住了
 * （写线程卡住 → {@code Undertow.stop()} 跟着卡 → 只看到 TimeoutException，看不出断言本身过没过）。
 * 因此「客户端断开能不能解开」<b>属于未验证</b>，不要引用它；真要用得重测。
 *
 * @author zcd
 */
@Disabled("重复的探针，结论见 SseWriteTimeoutTest；待删")
class BlockedSseWriteTest {

    @Test
    void blockedWrite_should_only_be_released_by_closing_the_connection() {
        fail("这一版没跑出结论（断言被 60 秒超时遮住）：不要引用「关连接/客户端断开能解开写」这个说法。");
    }
}
