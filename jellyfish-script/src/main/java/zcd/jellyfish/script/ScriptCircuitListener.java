package zcd.jellyfish.script;

/**
 * 熔断状态变化的观察者：打开与恢复各通知一次。
 * <p>
 * <b>为什么需要它</b>：熔断的直接后果是「工具开始返回一个奇怪的错误」，而这条线索只有调用点看得见
 * （模型看到、重试、放弃）。把它变成一条进程级告警，才能让「工具莫名失败」在日志里可归因——
 * 否则排查者的现场是「模型说工具不能用」，而日志里只有一次超时。
 * <p>
 * <b>为什么是回调而不是直接依赖事件通道</b>：本模块是纯库，{@code jellyfish-api} 只是 {@code provided}
 * 依赖，事件的发布权属于装配它的插件。用一个回调把「发生了什么」交出去，
 * 库就不必知道「谁来呈现」，测试也不必为了验一次状态迁移而搭一套事件通道。
 * <p>
 * <b>回调抛出的异常会被吞掉</b>：它发生在一次工具调用的路径上，
 * 让「记一条告警」失败去把用户的工具调用也弄失败，是本末倒置。
 *
 * @author zcd
 */
public interface ScriptCircuitListener {

    /**
     * 熔断已打开（含转永久）。
     * <p>
     * 只在状态**进入**打开态时调用一次，不会在熔断期间反复调用——重复通知会把
     * 「打开」这件事淹没在噪声里，而它本来只需要被看见一次。
     *
     * @param scriptId 脚本标识
     * @param state    打开后的状态：{@link ScriptCircuitBreaker.State#OPEN} 或
     *                 {@link ScriptCircuitBreaker.State#PERMANENT}
     * @param detail   可读详情，用于告警文案
     */
    void onOpened(String scriptId, ScriptCircuitBreaker.State state, String detail);

    /**
     * 熔断已恢复（半开探测成功）。
     *
     * @param scriptId 脚本标识
     * @param detail   可读详情，用于告警文案
     */
    void onRecovered(String scriptId, String detail);
}
