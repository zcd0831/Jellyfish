package io.github.zcd0831.jellyfish.domain.action.model;

/**
 * 线协议选择：厂商差异是数据、协议差异是适配器。
 * 规则出处：05-action §5.4.2、§5.6。
 */
public enum ChatProtocol {
    OPENAI_CHAT,
    ANTHROPIC_MESSAGES,
    GEMINI
}
