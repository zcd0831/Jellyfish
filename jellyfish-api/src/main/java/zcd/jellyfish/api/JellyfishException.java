package zcd.jellyfish.api;

/**
 * Jellyfish 统一运行时异常。
 * <p>
 * 插件实现与核心内部都只抛出该异常，便于 core 在 ReAct 循环中统一捕获并转为可读错误，
 * 避免把厂商 SDK 的异常类型泄漏到调用方。
 *
 * @author zcd
 */
public class JellyfishException extends RuntimeException {

    /**
     * 构造无描述的异常。
     */
    public JellyfishException() {
        super();
    }

    /**
     * 构造带描述的异常。
     *
     * @param message 错误描述
     */
    public JellyfishException(String message) {
        super(message);
    }

    /**
     * 使用原始异常作为 cause 构造异常。
     *
     * @param cause 原始异常
     */
    public JellyfishException(Throwable cause) {
        super(cause);
    }

    /**
     * 构造带描述与 cause 的异常。
     *
     * @param message 错误描述
     * @param cause   原始异常
     */
    public JellyfishException(String message, Throwable cause) {
        super(message, cause);
    }
}
