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
     * 序列化版本号。
     * <p>
     * <b>本内核没有把异常跨进程序列化的路径</b>（没有 RPC，也不把异常写进落盘文件），因此它眼下只是
     * 满足可序列化类的规范。之所以显式写出来而不是交给默认计算：异常是<b>公共契约</b>——
     * 插件作者的 catch 子句依赖它的类型，哪天真要跨边界传它时，缺这个字段会让「版本不同」表现为
     * 一次反序列化失败，而不是一句可读的提示。api 层另一个对外错误类型
     * {@link zcd.jellyfish.api.extension.ExtensionException} 同样补上。
     */
    private static final long serialVersionUID = 1L;

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
