package io.github.zcd0831.jellyfish.infrastructure.model;

import io.github.zcd0831.jellyfish.application.shared.CancellationToken;

/**
 * 生产用等待：睡着时挂一个令牌回调，终止一到就醒（Retry-After 可以是 60 秒）。
 * 规则出处：08-application §8.9.4.1（模型调用与退避都听同一个令牌）。
 */
final class TokenAwareSleeper implements Sleeper {

    @Override
    public void sleep(long millis, CancellationToken token) throws InterruptedException {
        final Object lock = new Object();
        Runnable wake = token.onCancel(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    lock.notifyAll();
                }
            }
        });
        try {
            synchronized (lock) {
                long deadline = System.currentTimeMillis() + millis;
                while (!token.isCancelled()) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        return;
                    }
                    lock.wait(remaining);
                }
            }
        } finally {
            wake.run();   // 注销回调：长会话里不能无限堆积
        }
    }
}
