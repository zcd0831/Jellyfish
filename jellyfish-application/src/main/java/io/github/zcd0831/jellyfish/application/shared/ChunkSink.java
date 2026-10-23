package io.github.zcd0831.jellyfish.application.shared;

/**
 * 流式增量只喂界面，不进 Record。
 * 规则出处：技术决策 D06。
 */
public interface ChunkSink {

    ChunkSink NOOP = delta -> { };

    void accept(String delta);
}
