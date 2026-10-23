package io.github.zcd0831.jellyfish.domain.action.model;

/**
 * 线协议选择：厂商差异是数据、协议差异是适配器。
 * 规则出处：模块-行动 §3.2、§5、技术决策 D16 第 1.1 条。
 */
public enum ChatProtocol {
    OPENAI_CHAT,
    ANTHROPIC_MESSAGES,
    GEMINI
}
