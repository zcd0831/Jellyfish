package zcd.jellyfish.script.protocol;

import zcd.jellyfish.api.JellyfishException;

/**
 * 脚本连接不可用：网关进程退出、管道断裂或写入失败。
 * <p>
 * <b>这是 L3（整门语言不可用）</b>，与 L1/L2（某个脚本失败）必须分开：L1/L2 的处置是
 * 「计入该脚本的熔断」，而 L3 的处置是「整门语言的调用全部失败，提示用户 {@code /reload}」。
 * 两者混在一起会让一个脚本把网关搞崩后，其它脚本继续在半死状态下产生一堆无意义的超时。
 * <p>
 * <b>它会一次性唤醒所有等待者</b>：进程已经不存在了，让剩下的人各自等到超时毫无意义——
 * 那只会把一次故障放大成 N 个超时周期。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public class ScriptConnectionException extends JellyfishException {

    /**
     * 构造异常。
     *
     * @param message 错误描述
     * @param cause   原始异常，可为 {@code null}
     */
    public ScriptConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
