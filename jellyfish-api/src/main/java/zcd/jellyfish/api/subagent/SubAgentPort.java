package zcd.jellyfish.api.subagent;

/**
 * 子代理委派端口：插件驱动子代理 run 的唯一入口。
 * <p>
 * <b>为什么是一个「端口」而不是一个扩展点</b>：{@code PluginContext.handle} / {@code contribute}
 * 的调用方向是「内核找插件」，插件根本没有 invoke 内核处理器的入口（{@code PluginContext} 刻意不给）。
 * 把委派做成扩展点，插件仍然调不动它——方向错了。委派与 {@code emit} / {@code submit} / {@code present}
 * 同类：插件往外发起的出向边。
 * <p>
 * <b>它受全部既有约束</b>：开关、深度、单回合扇出、全局并发、墙钟与 token 预算、取消传播，
 * 与 {@code task} 工具触发的委派走<b>同一条代码路径</b>，因此行为逐字段一致。
 * 插件不需要（也不应该）自己造第二套并发控制——并发度由内核的 governor 决定，超出的 run 在内核侧排队。
 * <p>
 * <b>拿不到端口时会得到一个「永远拒绝」的实现</b>（{@link #unavailable()}）：插件因此不必为
 * 「内核版本旧」写分支，与 {@code ToolOutputSink.NOOP} / {@code CancellationToken.NONE} 同一口径。
 * 它<b>不抛异常</b>——能力缺失不该以异常的形式出现在插件的正常路径上。
 * <p>
 * <b>线程</b>：{@link #spawn(DelegationRequest)} 与 {@link DelegationHandle#await()} 都应在
 * 发起它的那条线程上调用（通常是某次工具调用的执行线程）。内核侧没有共享的调用状态。
 *
 * @author zcd
 */
public interface SubAgentPort {

    /**
     * 派生一次委派：立即返回句柄，不阻塞。
     * <p>
     * 准入被拒（开关关闭、层数 / 预算用尽、类型未知或不可委派、当前没有进行中的回合）时，
     * 返回的句柄直接带着 {@link DelegationStatus#REJECTED} 的结果，而不是抛异常——
     * 编排方因此只需 {@code spawn → await} 两步。
     *
     * @param request 委派请求，不可为 {@code null}
     * @return 句柄，保证非 {@code null}
     */
    DelegationHandle spawn(DelegationRequest request);

    /**
     * 取一个「永远拒绝」的端口实现。
     * <p>
     * 每次调用都返回同一个实例（无状态）；拒绝理由是固定的一句中文，说明当前内核没有提供这个能力。
     *
     * @return 端口实现，保证非 {@code null}
     */
    static SubAgentPort unavailable() {
        return UnavailablePort.INSTANCE;
    }

    /**
     * 能力缺失时的占位实现。
     */
    final class UnavailablePort implements SubAgentPort {

        /** 唯一实例。 */
        private static final UnavailablePort INSTANCE = new UnavailablePort();

        /** 能力缺失的拒绝理由。 */
        private static final String REASON = "当前内核没有提供子代理委派能力";

        /**
         * 工具类，禁止外部实例化。
         */
        private UnavailablePort() {
        }

        @Override
        public DelegationHandle spawn(DelegationRequest request) {
            return DelegationHandle.settled(DelegationResult.rejected(REASON));
        }
    }
}
