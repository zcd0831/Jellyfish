package zcd.jellyfish.cli.mode;

import zcd.jellyfish.cli.StartupOptions;
import zcd.jellyfish.cli.console.ConsoleIO;

/**
 * TUI 模式：命令行界面、有交互，用 TamboUI 构建（尚未实现）。
 * <p>
 * <b>落地时的形态</b>：对话式为主——消息滚动区 + 输入框 + 状态栏（agent / model / 权限模式 / token 用量）；
 * 会话列表与待办沿用命令的文本输出（{@code /session}、{@code /todo}），不单开面板。
 * <p>
 * <b>与 CLI 模式共享的规则</b>（落地时必须原样复用，不得另起一套）：
 * <ol>
 *     <li>智能入口只有 {@code AgentHarness.chat}，命令入口只有 {@code CommandManager}；</li>
 *     <li>命令与对话的分流判据只有 {@code CommandManager.isCommand}；</li>
 *     <li>每轮<b>现读</b>当前会话（{@code SessionManager.current()}），不缓存 sessionId，
 *     这样 {@code /new} {@code /resume} 之后立刻生效；</li>
 *     <li>{@code /exit} 归外壳，不注册为命令。</li>
 * </ol>
 * <p>
 * <b>开工前置条件（必须先验证，再动手写界面）</b>：TamboUI 目前<b>只有 snapshot 构建</b>且官方标注
 * experimental（API 可能变动）。第一步是在 JDK 1.8 下冒烟 {@code tamboui-tui} + {@code tamboui-jline3-backend}：
 * 能编译、能进备用屏、能收到按键事件。若不通过，换 Lanterna 或用 JLine 3 手写，不要在半成品上继续投入。
 *
 * @author zcd
 */
public final class TuiRunMode extends PlaceholderRunMode {

    /**
     * 构造 TUI 占位模式。
     *
     * @param console 输出面板，不可为 {@code null}
     */
    public TuiRunMode(ConsoleIO console) {
        super(console);
    }

    @Override
    StartupOptions.Mode mode() {
        return StartupOptions.Mode.TUI;
    }
}
