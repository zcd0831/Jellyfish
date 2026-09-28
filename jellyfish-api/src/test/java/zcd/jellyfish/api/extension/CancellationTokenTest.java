package zcd.jellyfish.api.extension;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link CancellationToken} 缺省实例的单元测试：锁住「永不取消」这条语义。
 *
 * @author zcd
 */
@DisplayName("CancellationToken 永不取消的令牌")
class CancellationTokenTest {

    @Test
    void none_should_never_report_cancelled() {
        // Then
        assertFalse(CancellationToken.NONE.isCancelled());
    }

    @Test
    void none_should_not_run_registered_callback() {
        // Given
        AtomicBoolean ran = new AtomicBoolean(false);

        // When：拿不到真实令牌的工具按「不会取消」处理，注册的回调永远不该执行
        CancellationToken.NONE.onCancel(() -> ran.set(true));

        // Then
        assertFalse(ran.get());
    }
}
