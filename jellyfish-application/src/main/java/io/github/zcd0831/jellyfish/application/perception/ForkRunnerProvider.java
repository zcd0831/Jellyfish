package io.github.zcd0831.jellyfish.application.perception;

/**
 * 延迟取 fork 执行点：裁判 / 检索 → fork → 内循环 → 工具执行 → 计划工具 → 检索 是构造环，
 * 用"取的时候才要"打断它。应用层不引任何容器类型。
 */
public interface ForkRunnerProvider {

    InternalSessionRunner get();
}
