package zcd.jellyfish.api.ask;

/**
 * 向用户提问的端口：工具与插件把问题交给当前外壳的唯一入口。
 * <p>
 * <b>为什么是一个「端口」而不是一个扩展点</b>：{@code PluginContext.handle} / {@code contribute}
 * 的调用方向是「内核找插件」，插件根本没有 invoke 内核处理器的入口。把提问做成扩展点，插件仍然
 * 调不动它——方向错了。提问与 {@code emit} / {@code submit} / {@code present} /
 * {@code delegations()} 同类：是插件往外发起的出向边。同理，它<b>不是
 * {@code ExtensionRequest}</b> 的子类型，因此不参与脚本插件的扩展点能力分档。
 * <p>
 * <b>它是阻塞的</b>：{@link #ask(AskRequest)} 在调用线程上等到用户给出答复或等待超时。
 * 工具执行本身是同步内联的（在 {@code react} 线程上跑完），人工审批也走同一条路，
 * 因此阻塞是这里的正确形状——提问方要的正是「拿到答案再往下走」。
 * 代价也相同：一次提问占用 {@code react} 池的一条线程，因此超时是必须的（见
 * {@code AskChannel}）。
 * <p>
 * <b>拿不到端口时会得到一个「永远答复不可用」的实现</b>（{@link #unavailable()}）：插件因此
 * 不必为「内核版本旧」写分支，与 {@code SubAgentPort.unavailable()} /
 * {@code ToolOutputSink.NOOP} 同一口径。它<b>不抛异常</b>——能力缺失不该以异常的形式出现在
 * 插件的正常路径上。
 * <p>
 * <b>它不保证有人回答</b>：{@code -cli} 等没有交互界面的外壳不挂载答复者，{@link #ask} 会立刻
 * 返回 {@link AskAnswer.Status#UNAVAILABLE}。这<b>不是安全边界</b>：提问拿不到答案不等于哪次
 * 工具调用被拒绝，权限判定的口径不受本端口影响。
 *
 * @author zcd
 */
public interface AskPort {

    /**
     * 向用户提问并等待答复。
     * <p>
     * <b>调用线程会一直等到某个终态</b>：用户答复、用户放弃、等待超时、或此处无法提问。
     * 除非实现方明确说明，本方法不会抛异常来表示「没人回答」——那四种情形都是返回值的语义。
     *
     * @param request 提问请求，不可为 {@code null}
     * @return 答复，保证非 {@code null}
     */
    AskAnswer ask(AskRequest request);

    /**
     * 取一个「永远答复不可用」的端口实现。
     * <p>
     * 每次调用都返回同一个实例（无状态）；理由是固定的一句中文，说明当前内核没有提供这个能力。
     *
     * @return 端口实现，保证非 {@code null}
     */
    static AskPort unavailable() {
        return UnavailablePort.INSTANCE;
    }

    /**
     * 能力缺失时的占位实现。
     */
    final class UnavailablePort implements AskPort {

        /** 唯一实例。 */
        private static final UnavailablePort INSTANCE = new UnavailablePort();

        /** 能力缺失的说明。 */
        private static final String REASON = "当前内核没有提供向用户提问的能力";

        /**
         * 工具类，禁止外部实例化。
         */
        private UnavailablePort() {
        }

        @Override
        public AskAnswer ask(AskRequest request) {
            return AskAnswer.unavailable(REASON);
        }
    }
}
