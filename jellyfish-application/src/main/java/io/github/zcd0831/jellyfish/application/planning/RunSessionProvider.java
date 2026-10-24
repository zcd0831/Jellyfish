package io.github.zcd0831.jellyfish.application.planning;

/**
 * 延迟取用例：子代理启动器 → 用例 → 外循环 → 内循环 → 工具执行 → 启动器 是构造环，
 * 用"取的时候才要"打断它。应用层不引任何容器类型（D04-1）。
 */
public interface RunSessionProvider {

    RunSessionUseCase get();
}
