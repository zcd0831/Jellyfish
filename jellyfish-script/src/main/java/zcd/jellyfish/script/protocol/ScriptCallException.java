package zcd.jellyfish.script.protocol;

import zcd.jellyfish.api.JellyfishException;

/**
 * 脚本调用失败：网关明确回报了一个协议错误。
 * <p>
 * <b>为什么要把错误码带上来而不是只留一句消息</b>：错误码决定了谁来处置。
 * {@code -32001}（熔断中）不该再计一次失败、也不该杀 worker；{@code -32000}（脚本业务异常）要计入熔断；
 * {@code -32004}（清单与实现不一致）说明用户要改的是脚本而不是重试。都压成字符串的话，
 * 上层只能靠匹配文案来判断，那是最脆弱的一种耦合。
 * <p>
 * <b>它是 L1/L2（脚本自身失败）的统一出口</b>。L3（整门语言不可用，如网关崩溃、管道断裂）
 * 不走这里：那是连接问题、错误码来自内核而非脚本，见 {@link ScriptConnectionException}。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public class ScriptCallException extends JellyfishException {

    /** 网关回报的错误码。 */
    private final int code;

    /**
     * 构造异常。
     *
     * @param code    网关回报的错误码
     * @param message 错误描述
     */
    public ScriptCallException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 获取网关回报的错误码。
     *
     * @return 错误码
     */
    public int code() {
        return code;
    }

    /**
     * 判断是否处于熔断中。
     * <p>
     * 调用方据此避免「熔断期间每次失败都再计一次失败」这种自我加强的循环。
     *
     * @return 熔断中返回 {@code true}
     */
    public boolean isCircuitOpen() {
        return code == ScriptProtocol.CODE_CIRCUIT_OPEN;
    }

    /**
     * 判断是否为脚本业务失败。
     *
     * @return 脚本业务失败返回 {@code true}
     */
    public boolean isScriptFailure() {
        return code == ScriptProtocol.CODE_SCRIPT_FAILURE;
    }

    /**
     * 判断是否为清单与实现不一致。
     * <p>
     * 这类失败重试没有意义，只有改脚本或改清单才能消除，因此值得让上层单独识别。
     *
     * @return 不一致返回 {@code true}
     */
    public boolean isManifestMismatch() {
        return code == ScriptProtocol.CODE_MANIFEST_MISMATCH;
    }
}
