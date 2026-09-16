package zcd.jellyfish.script.event;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 事件推送目标：由网关实现，把「事件往哪儿发」与「发什么」解耦。
 * <p>
 * 抽成接口的直接好处是事件桥接可以在完全没有进程的情况下被单测覆盖——
 * 而它恰恰是内核通知线程路径上的一段代码，最不该只能靠端到端用例验证。
 *
 * @author zcd
 */
public interface ScriptEventTarget {

    /**
     * 网关是否已启动。
     * <p>
     * 事件不负责拉起网关（懒启动只由调用触发），因此推送前要先问这一句。
     *
     * @return 已启动返回 {@code true}
     */
    boolean isRunning();

    /**
     * 推送一条事件。
     * <p>
     * 实现**不得**等待任何应答：事件是单向的，等应答就把通知线程的及时性又交回给了对端。
     *
     * @param eventName 事件名
     * @param payload   事件字段表
     */
    void notifyEvent(String eventName, JsonNode payload);
}
