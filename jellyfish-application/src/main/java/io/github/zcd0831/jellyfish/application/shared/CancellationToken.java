package io.github.zcd0831.jellyfish.application.shared;

/**
 * 取消信号：阻塞点（ask / 退避 / 循环边界）必须能被它打断。
 * 规则出处：技术决策 D05（取消必须能打断阻塞）、D10（退避可取消）、§8.4.1。
 */
public interface CancellationToken {

    boolean isCancelled();

    void onCancel(Runnable callback);

    void throwIfCancelled();

    /** 不参与取消的调用（测试复跑、起标题这类轮次外的短调用）用它。 */
    static CancellationToken never() {
        return new CancellationToken() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void onCancel(Runnable callback) {
                // 永不触发
            }

            @Override
            public void throwIfCancelled() {
                // 永不触发
            }
        };
    }
}
