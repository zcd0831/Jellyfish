package zcd.jellyfish.core.compact;

import zcd.jellyfish.api.JellyfishException;

/**
 * 压缩不可用：没有任何插件给出可用的摘要策略。
 * <p>
 * <b>为什么要有这个类型</b>：{@code /compact} 的两种失败需要说清是不同的事——「没有足够的历史可压缩」是
 * 用户要的动作没发生，「没有插件提供压缩策略」是<b>这个功能在本机压根不存在</b>。两者的处置方式也不同：
 * 前者再聊几句就有了，后者要去装插件。因此它需要一个可判定的类型，而不是靠比对异常文本。
 * <p>
 * <b>为什么会不可用</b>：压缩的摘要指令由插件提供（内核只提供机制）。没有插件 → 没有指令 → 没有可发给
 * 模型的摘要请求。因此这不是回退到某个内置策略，而是缺件。<b>自动压缩那条路会静默让路</b>
 * （见 {@link ConversationCompactor#autoCompactIfNeeded}），用户主动发起才会看到这条消息。
 * <p>
 * 继承 {@link JellyfishException} 以维持「异常统一」的口径，仅用于调用点分流。
 *
 * @author zcd
 */
public class CompactionUnavailableException extends JellyfishException {

    /**
     * 序列化版本号。
     * <p>
     * 内核没有把异常跨进程序列化的路径（无 RPC，也不把异常写进落盘文件），因此它眼下只满足
     * 可序列化类的规范；显式声明而不是交给默认计算，是因为异常是<b>公共契约</b>——哪天真要跨边界传它时，
     * 缺这个字段会让「版本不同」表现为一次反序列化失败，而不是一句可读提示。
     */
    private static final long serialVersionUID = 1L;

    /**
     * 构造压缩不可用异常。
     *
     * @param message 失败原因，会原样呈现给用户
     */
    public CompactionUnavailableException(String message) {
        super(message);
    }
}
