package zcd.jellyfish.cli.mode;

import zcd.jellyfish.cli.ExitCodes;
import zcd.jellyfish.cli.StartupOptions;
import zcd.jellyfish.cli.console.ConsoleIO;

import java.util.Objects;

/**
 * 尚未实现的启动模式的共用占位。
 * <p>
 * <b>为什么占位也要能「跑起来」并给出退出码</b>：{@code -tui} / {@code -server} 出现在用法里，
 * 用户就会去敲。沉默地什么都不做、或者返回 0，都会让脚本误判成功；这里统一给
 * {@link ExitCodes#NOT_IMPLEMENTED} 并指名替代方案。
 * <p>
 * <b>为什么由 {@code Launcher} 跳过内核启动</b>：占位模式不碰 {@code AgentHarness}，
 * 因此不会加载配置、不会起事件线程、不会扫描插件目录——「看起来起来了却什么都做不了」比直接报错更糟。
 *
 * @author zcd
 */
abstract class PlaceholderRunMode implements RunMode {

    /** 输出面板。 */
    private final ConsoleIO console;

    /**
     * 构造占位模式。
     *
     * @param console 输出面板，不可为 {@code null}
     */
    PlaceholderRunMode(ConsoleIO console) {
        this.console = Objects.requireNonNull(console, "console must not be null");
    }

    /**
     * 获取本模式对应的启动模式枚举。
     *
     * @return 启动模式
     */
    abstract StartupOptions.Mode mode();

    @Override
    public boolean isImplemented() {
        return false;
    }

    @Override
    public int run(StartupOptions options) {
        console.writeErrLine(mode().getFlag() + " 尚未实现，请使用 -cli（单次调用）。");
        return ExitCodes.NOT_IMPLEMENTED;
    }
}
