package zcd.jellyfish.cli.mode;

import zcd.jellyfish.cli.StartupOptions;

/**
 * 启动模式：一种「谁来驱动 ReAct 回合」的具体做法。
 * <p>
 * <b>为什么抽成接口</b>：三种模式（CLI 单次 / TUI 交互 / Server HTTP）共享同一个 {@code main}、同一份 DI 装配
 * 与同一个 {@code AgentHarness}，只有「输入从哪来、结果往哪去」不同。把差异收在实现类里之后，
 * {@link zcd.jellyfish.cli.Launcher} 只做「选实现 + 管生命周期」，将来补齐 TUI / Server 时它一行不用改。
 * <p>
 * <b>为什么显式区分「是否已实现」</b>：占位模式与真实现的差别不只是「跑起来有没有效果」——
 * 内核（事件通道、插件运行时、索引）根本不该为一个空壳启动。用一个方法把这个判断交给模式自己回答，
 * 比在 {@code Launcher} 里按模式名枚举更不容易漏。
 *
 * @author zcd
 */
public interface RunMode {

    /**
     * 判断本模式在当前版本是否已实现。
     *
     * @return 已实现返回 {@code true}；占位实现返回 {@code false}
     */
    boolean isImplemented();

    /**
     * 以本模式运行。
     *
     * @param options 启动参数，不可为 {@code null}
     * @return 进程退出码，取值见 {@link zcd.jellyfish.cli.ExitCodes}
     */
    int run(StartupOptions options);
}
