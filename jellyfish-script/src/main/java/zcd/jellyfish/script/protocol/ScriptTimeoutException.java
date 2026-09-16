package zcd.jellyfish.script.protocol;

import zcd.jellyfish.api.JellyfishException;

/**
 * 脚本调用超时：在约定时间内没有等到应答。
 * <p>
 * <b>为什么超时是一个独立类型而不是一个错误码</b>：超时不是脚本说的话，而是 Java 侧的判断
 * （协议里没有任何一方会回报它）。把它编成一个错误码，就等于让「本地观察」与「远端回报」
 * 混在同一个字段里，将来想看「到底有多少次是脚本自己失败的」就必须先剔除那一个魔法值。
 * <p>
 * 它还是<b>唯一需要杀 worker 的失败</b>：脚本卡住时连接看起来完全正常，只有把 worker 连同它
 * 挂死的那个线程一起丢掉，下一次调用才可能成功。因此这个类型的存在本身就是给调用点的信号——
 * 捕获它、杀 worker、计入熔断，然后照常把失败回灌给模型。
 * <p>
 * <b>迟到响应按 id 丢弃、不做补偿</b>：{@link ScriptRpc} 在超时时已把该 id 从等待表移除，
 * 之后到达的响应找不到等待者，只会被记一条告警。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public class ScriptTimeoutException extends JellyfishException {

    /** 已等待的毫秒数。 */
    private final long waitedMillis;

    /**
     * 构造异常。
     *
     * @param message      错误描述
     * @param waitedMillis 已等待的毫秒数
     */
    public ScriptTimeoutException(String message, long waitedMillis) {
        super(message);
        this.waitedMillis = waitedMillis;
    }

    /**
     * 获取已等待的毫秒数。
     *
     * @return 已等待的毫秒数
     */
    public long waitedMillis() {
        return waitedMillis;
    }
}
