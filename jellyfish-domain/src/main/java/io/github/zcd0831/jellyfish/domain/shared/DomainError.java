package io.github.zcd0831.jellyfish.domain.shared;

/**
 * 上下文自己的错误类型实现它，应用层与外壳只认 {@code code()}。
 * 规则出处：技术决策 D11（错误码按上下文拆，统一 code 前缀）。
 */
public interface DomainError {

    String code();
}
