package io.github.zcd0831.jellyfish.application.shared;

/**
 * 终止信号：阻塞点（ask / 退避 / 循环边界 / 在途请求）必须能被它打断。
 * 规则出处：08-application §8.9.4.1（终止怎么到阻塞点）。
 */
public interface CancellationToken {

    boolean isCancelled();

    /**
     * 注册回调，返回<strong>注销句柄</strong>。
     * 调用方在一次调用结束时必须注销——每个会话只有一个令牌、每次模型调用注册一次，
     * 不注销的话长会话里回调会无限堆积（同时会在已被终止的令牌上反复触发）。
     */
    Runnable onCancel(Runnable callback);

    void throwIfCancelled();

    /** 不参与终止的调用（测试复跑、起标题这类轮次外的短调用）用它。 */
    static CancellationToken never() {
        return new CancellationToken() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public Runnable onCancel(Runnable callback) {
                return new Runnable() {
                    @Override
                    public void run() {
                        // 永不触发，也就没有需要注销的东西
                    }
                };
            }

            @Override
            public void throwIfCancelled() {
                // 永不触发
            }
        };
    }
}
