package io.github.zcd0831.jellyfish.domain.shared;

/**
 * 聚合 raise 的事实：提交后由应用层同步派发，只做通知。
 * 规则出处：08-application §8.8.3、模型图 §六。
 */
public interface DomainEvent {

    /** 事件名，用于日志与排障。 */
    String name();
}
