package io.github.zcd0831.jellyfish.domain.shared;

/**
 * 聚合 raise 的事实：提交后由应用层同步派发，只做通知。
 * 规则出处：模块-应用层 §7.3、模型图 §六。
 */
public interface DomainEvent {

    /** 事件名，用于日志与排障（与 D14 的 ev 字段一致）。 */
    String name();
}
