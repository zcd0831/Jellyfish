package io.github.zcd0831.jellyfish.infrastructure.model;

import java.util.Random;

/**
 * 重试的节奏：退避档位、Retry-After 的采信上限、以及"这次失败能不能重试"的载体。
 * 规则出处：08-application §8.10（同模型重试用尽再交给备用链）、§8.9.4.1。
 */
final class RetryPolicy {

    /** 退避档位：500ms / 1.5s / 4s；用尽即交给备用链。 */
    static final int[] BACKOFF_MILLIS = {500, 1500, 4000};

    /** Retry-After 超过这个值就不等了，直接降级。 */
    static final long MAX_RETRY_AFTER_SECONDS = 60L;

    private final Random jitter = new Random();

    static boolean exhausted(int attempt) {
        return attempt >= BACKOFF_MILLIS.length;
    }

    /** 抖动 ±20%：一堆会话同时被限流时不要挤在同一个时刻重来。 */
    long backoff(int attempt) {
        long base = BACKOFF_MILLIS[Math.min(attempt, BACKOFF_MILLIS.length - 1)];
        double factor = 0.8 + jitter.nextDouble() * 0.4;
        return Math.round(base * factor);
    }

    static boolean tooLongToWait(Long retryAfterMillis) {
        return retryAfterMillis != null && retryAfterMillis.longValue() > MAX_RETRY_AFTER_SECONDS * 1000;
    }

    /** `Retry-After: 5`（秒）。解析不出来就当没有，按自己的退避档位走。 */
    static Long retryAfterMillis(ChatTransport.Response response) {
        String header = response.retryAfter();
        if (header == null || header.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(header.trim()) * 1000L;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 可重试的失败：外层据此退避重试，重试用尽再交给备用链。 */
    static final class RetryableFailure extends RuntimeException {

        private final Long retryAfterMillis;

        RetryableFailure(String message, Long retryAfterMillis) {
            super(message);
            this.retryAfterMillis = retryAfterMillis;
        }

        RetryableFailure(String message, Long retryAfterMillis, Throwable cause) {
            super(message, cause);
            this.retryAfterMillis = retryAfterMillis;
        }

        Long retryAfterMillis() {
            return retryAfterMillis;
        }
    }
}
