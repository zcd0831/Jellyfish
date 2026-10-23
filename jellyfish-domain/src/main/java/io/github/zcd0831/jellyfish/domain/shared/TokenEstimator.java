package io.github.zcd0831.jellyfish.domain.shared;

/**
 * 领域层不持有分词器（那是端口的事），但推导窗口时需要"这段文字大约多少 token"。
 * 以函数入参传入，与 ContextWindowPolicy 值对象入参同构。
 * 规则出处：模块-感知 §7.1 规则 12、§8.5 第 ④ 步。
 */
public interface TokenEstimator {

    int estimate(String text);
}
