package io.github.zcd0831.jellyfish.infrastructure.model;

/** 响应体无法解析：属于"模型端点行为异常"，按不可重试处理。 */
final class ModelResponseFormatException extends RuntimeException {

    ModelResponseFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
