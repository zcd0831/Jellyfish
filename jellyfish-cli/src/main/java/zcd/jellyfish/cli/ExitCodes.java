package zcd.jellyfish.cli;

/**
 * 进程退出码：外壳与脚本之间的机器契约。
 * <p>
 * 刻意与「人类可读信息」分开：用法提示、错误说明都走 stdout / stderr，退出码只表达<b>成败类别</b>，
 * 让调用方无需解析文本就能判断该不该继续下一步。
 * <p>
 * 取值沿用 Unix 惯例：{@code 0} 成功；{@code 2} 用法错误（与多数命令行工具的约定一致），
 * 并刻意避开 shell 保留的 {@code 126} / {@code 127} 与信号码。
 *
 * @author zcd
 */
public final class ExitCodes {

    /** 正常结束。 */
    public static final int OK = 0;

    /** 参数或用法错误：未知参数、缺值、非法值、没有输入。 */
    public static final int USAGE_ERROR = 2;

    /** 启动失败：配置加载、插件运行时或 DI 装配抛出异常。 */
    public static final int STARTUP_ERROR = 3;

    /** 运行期失败：回合抛异常，或命令返回 {@code ERROR}。 */
    public static final int RUNTIME_ERROR = 4;

    /** 回合未收敛：达到最大轮次仍未给出最终回复。 */
    public static final int TRUNCATED = 6;

    /**
     * 回合被插件拦下：一句都没发给模型。
     * <p>
     * <b>为什么不归到 {@link #RUNTIME_ERROR}</b>：它不是运行失败——没抛异常、没被用户取消、
     * 没有资源故障。脚本对它的补救动作（改请求 / 找人确认）与对 {@code 4}（看日志排故障）完全不同。
     * 现有集合已经在做同类区分（{@code 0} = 未知命令 vs {@code 4} = 跑挂了），
     * 把「策略拦下」塞进 {@code 4} 会丢掉同一层的信息。
     * <p>
     * 该回合的 stdout <b>保持为空</b>（它没有回答），原因只进 stderr。
     */
    public static final int TURN_BLOCKED = 7;

    private ExitCodes() {
    }
}
