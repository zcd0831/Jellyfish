package zcd.jellyfish.cli.mode;

import zcd.jellyfish.api.RuntimeInfo;
import zcd.jellyfish.cli.StartupOptions;

import java.util.Optional;

/**
 * 启动模式：一种「谁来驱动 ReAct 回合」的具体做法。
 * <p>
 * <b>为什么抽成接口</b>：三种模式（CLI 单次 / TUI 交互 / Server HTTP）共享同一个 {@code main}、同一份 DI 装配
 * 与同一个 {@code AgentHarness}，只有「输入从哪来、结果往哪去」不同。把差异收在实现类里之后，
 * {@link zcd.jellyfish.cli.Launcher} 只做「选实现 + 管生命周期」，新增模式时它一行不用改。
 *
 * @author zcd
 */
public interface RunMode {

    /**
     * 运行前环境自检：由模式自己回答「当前环境跑不跑得起来」。
     * <p>
     * <b>为什么要单独一步</b>：某些模式对运行环境有硬要求（TUI 需要可交互终端），
     * 而环境不满足时启动内核是纯浪费——插件扫描、事件线程、HTTP 客户端池都会白起一遍，
     * 最后才在模式里发现跑不了。因此检查必须发生在 {@code Launcher} 启动内核<b>之前</b>。
     * <p>
     * <b>为什么不放在模式自己的 {@code run()} 里</b>：那时内核已经起来了，上面那些代价都已经付过。
     * <p>
     * 默认实现认为环境总是满足——多数模式只要求能读写标准流。
     *
     * @param options 启动参数，不可为 {@code null}
     * @return 环境不满足时返回可直接展示给用户的原因；满足时返回 {@link Optional#empty()}
     */
    default Optional<String> checkEnvironment(StartupOptions options) {
        return Optional.empty();
    }

    /**
     * 声明本模式对应的外壳种类。
     * <p>
     * <b>为什么由模式自己回答</b>：外壳种类决定插件看到的 {@link RuntimeInfo}（有没有可交互界面、
     * 能不能弹审批），而这三个事实完全由模式决定。收在实现类里之后，装配根只负责把结果写进持有者，
     * 不需要自己维护一份「启动参数 → 外壳」的映射。
     *
     * @return 外壳种类，保证非 {@code null}
     */
    RuntimeInfo.Shell shell();

    /**
     * 以本模式运行。
     *
     * @param options 启动参数，不可为 {@code null}
     * @return 进程退出码，取值见 {@link zcd.jellyfish.cli.ExitCodes}
     */
    int run(StartupOptions options);
}
