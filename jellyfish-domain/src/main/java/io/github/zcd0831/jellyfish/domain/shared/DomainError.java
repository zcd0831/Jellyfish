package io.github.zcd0831.jellyfish.domain.shared;

/**
 * 上下文自己的错误类型实现它，应用层与外壳只认 {@code code()}。
 */
public interface DomainError {

    String code();
}
