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

    /** 模式尚未实现：本轮为 {@code -tui} / {@code -server}。 */
    public static final int NOT_IMPLEMENTED = 5;

    /** 回合未收敛：达到最大轮次仍未给出最终回复。 */
    public static final int TRUNCATED = 6;

    private ExitCodes() {
    }
}
