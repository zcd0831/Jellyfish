package io.github.zcd0831.jellyfish.infrastructure.model;

/**
 * 流式请求拿到 HTTP 错误状态码：交给 {@link ModelInvoker} 用同一份失败映射分流
 * （4xx 不重试、429/5xx 才退避），不在这里决定重试与否。
 * 规则出处：模块-基础设施层 §10 第 5 条。
 */
final class HttpStatusException extends RuntimeException {

    private final int status;
    private final String body;
    private final String retryAfter;

    HttpStatusException(int status, String body, String retryAfter) {
        super("流式请求失败：HTTP " + status + " " + (body == null ? "" : body));
        this.status = status;
        this.body = body;
        this.retryAfter = retryAfter;
    }

    int status() {
        return status;
    }

    String body() {
        return body;
    }

    String retryAfter() {
        return retryAfter;
    }
}
