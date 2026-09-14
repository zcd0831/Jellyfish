package zcd.jellyfish.tui;

import dev.tamboui.layout.Size;
import dev.tamboui.toolkit.app.ToolkitApp;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.toolkit.event.GlobalEventHandler;
import dev.tamboui.toolkit.event.KeyEventHandler;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.core.AgentHarness;
import zcd.jellyfish.core.ReActTurn;
import zcd.jellyfish.infra.command.CommandManager;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.model.ResolvedModel;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;

import java.util.Objects;

/**
 * TUI 外壳：交互式终端界面的组装与事件循环。
 * <p>
 * <b>它只做四件事</b>：把按键翻译成动作、把动作交给内核、把内核的产出投影到屏幕、把会话运行态贴到状态栏。
 * 任何「思考」都不在这里——智能入口只有 {@link AgentHarness#chat}，命令入口只有 {@link CommandManager}，
 * 与 {@code CliRunMode} 走的是同一对门面，这条口径从 CLI 轮延续下来，不另起一套。
 * <p>
 * <b>两条必须原样复用的规则</b>：
 * <ol>
 *     <li><b>分流判据只有 {@code CommandManager.isCommand}</b>——它只做语法判定、不查注册表，
 *     因此插件在启动后注册的命令也能被同一路径命中；</li>
 *     <li><b>每轮现读当前会话</b>（{@link SessionManager#current()}），不缓存 sessionId，
 *     这样 {@code /new}、{@code /resume} 之后立刻生效，界面也会跟着切到新会话的内容。</li>
 * </ol>
 * <p>
 * <b>回合为什么是异步的</b>：{@code chat} 返回 {@link ReActTurn} 句柄并在专用线程池里推进，
 * 界面在自己的事件循环里继续跑。若在这里调 {@code await()}，界面会在整个回合期间冻住——
 * 连 {@code Esc} 都收不到。
 * <p>
 * <b>为什么回合进行中拒绝新输入</b>：{@code ReActLooper} 会立刻把用户消息追加进会话，
 * 并发提交两条消息会让历史里的顺序与用户实际发送顺序不一致。拒绝比静默排队更可解释——
 * 用户能看到「正在生成」以及「按 Esc 可中断」。
 *
 * @author zcd
 */
public final class TuiApp extends ToolkitApp {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(TuiApp.class);

    /** 智能入口。 */
    private final AgentHarness harness;

    /** 命令域服务。 */
    private final CommandManager commands;

    /** 会话域服务。 */
    private final SessionManager sessions;

    /** 模型门面，仅用于状态栏展示上下文长度。 */
    private final ModelManager models;

    /** 视图状态。 */
    private final ChatState chatState = new ChatState();

    /** 输入区视图。 */
    private final ChatInputView input;

    /** 版式。 */
    private final ChatShell shell;

    /** 输入框的按键截胡处理器：输入元素是聚焦元素，按键必经它，因此发送与中断都挂在这里。 */
    private final InputKeys inputKeys = new InputKeys();

    /** 上一帧显示的会话标识，用于识别会话切换并清掉属于旧会话的提示。 */
    private String renderedSessionId;

    /**
     * 构造 TUI 外壳。
     *
     * @param harness  智能入口，不可为 {@code null}
     * @param commands 命令域服务，不可为 {@code null}
     * @param sessions 会话域服务，不可为 {@code null}
     * @param models   模型门面，不可为 {@code null}
     */
    public TuiApp(AgentHarness harness, CommandManager commands, SessionManager sessions, ModelManager models) {
        this.harness = Objects.requireNonNull(harness, "harness must not be null");
        this.commands = Objects.requireNonNull(commands, "commands must not be null");
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.models = Objects.requireNonNull(models, "models must not be null");
        this.input = new ChatInputView(inputKeys);
        this.shell = new ChatShell(input);
    }

    /**
     * 调整终端能力开关。
     * <p>
     * <b>只开括号粘贴，刻意不开鼠标捕获</b>（实测结论）：
     * <ul>
     *     <li>{@code bracketedPaste} 默认 {@code false}，此时终端不把粘贴内容包起来，
     *     而 {@code \r} 与 {@code \n} 都被解码成裸 {@code ENTER}——粘贴一段多行文本
     *     会把每个换行变成一次操作，等于把一次粘贴拆成多条消息。开启后
     *     {@code PasteEvent} 一次送达完整文本，换行不再被当按键；</li>
     *     <li>{@code mouseCapture} / {@code mouseMotion} 保持关闭：开启后终端的鼠标选择会被应用截走，
     *     用户复制屏幕上文本必须按住修饰键（macOS 为 Option）。<b>滚轮滚动是刻意的功能缺口</b>，
     *     代价是换来的选择自由太大。消息区滚动只靠 {@code PageUp} / {@code PageDown} / {@code End}。</li>
     * </ul>
     *
     * @return 终端配置
     */
    @Override
    protected TuiConfig configure() {
        return TuiConfig.builder().bracketedPaste(true).build();
    }

    @Override
    protected void onStart() {
        // 兼容兼底的全局处理器：只在滚动键没被输入框吃掉时才会收到（焦点丢失等边界情况）。
        // 发送与中断不依赖它——实测全局处理器排在聚焦元素之后，靠它接不到 Enter。
        runner().eventRouter().addGlobalHandler(new ScrollFallback());
    }

    @Override
    protected Element render() {
        Session session = sessions.current();
        Size size = runner().tuiRunner().terminal().size();
        int width = ChatShell.messageAreaWidth(size.width());
        int rows = ChatShell.messageAreaRows(size.height(), input.panelRows());

        String sessionId = session == null ? null : session.getSessionId();
        syncSession(sessionId);

        ChatState.View view = chatState.view(sessionId,
                session == null ? null : session.getMessages(), width, rows,
                TranscriptProjector.DEFAULT_MAX_MESSAGES);

        String status = StatusBarView.render(session, null, contextLengthOf(session));
        String hint = view.hiddenBelowHint();
        if (hint != null) {
            // 提示放在状态栏而不是消息区：消息区的行数已被投影切片占满，
            // 额外加一行会把最新的一行挤出可视区——而「跟随底部」时用户最想看的正是那一行
            status = status + "   " + hint;
        }
        return shell.render(view, title(session), status);
    }

    /**
     * 识别会话切换并清掉属于旧会话的外壳提示。
     * <p>
     * 提示是「外壳刚刚说过的话」，属于上一次交互的上下文；换了会话还留着，会让用户以为那是新会话的一部分。
     * 消息区本身由投影自动跟随，不需要在这里做任何事——这正是「视图 = 会话投影」的好处。
     *
     * @param sessionId 当前会话标识，可为 {@code null}
     */
    private void syncSession(String sessionId) {
        if (!Objects.equals(renderedSessionId, sessionId)) {
            chatState.clearNotices();
            renderedSessionId = sessionId;
        }
    }

    /**
     * 处理用户提交。
     */
    private void submit() {
        if (input.isBlank()) {
            return;
        }
        if (chatState.isTurnRunning()) {
            chatState.appendNotice("回合进行中：按 Esc 可中断。", false);
            return;
        }
        String text = input.takeText();
        // 外壳自有命令必须先截胡：交给命令域只会得到 UNKNOWN，而外壳其实完全听得懂
        if (ShellCommand.isShellCommand(text)) {
            quit();
            return;
        }
        String sessionId = currentSessionId();
        if (commands.isCommand(text)) {
            executeCommand(text, sessionId);
            return;
        }
        startTurn(text, sessionId);
    }

    /**
     * 执行一条命令，并把结果作为外壳提示贴上屏幕。
     *
     * @param text      命令原文
     * @param sessionId 当前会话标识
     */
    private void executeCommand(String text, String sessionId) {
        try {
            CommandResult result = commands.execute(text, sessionId);
            chatState.appendNotice(result.getOutput(), result.getKind() == CommandResult.Kind.ERROR);
        } catch (JellyfishException e) {
            LOG.warn("TUI 命令执行失败：{}", e.getMessage());
            chatState.appendNotice("命令执行失败：" + e.getMessage(), true);
        }
    }

    /**
     * 发起一次 ReAct 回合。
     *
     * @param text      用户输入
     * @param sessionId 当前会话标识
     */
    private void startTurn(String text, String sessionId) {
        // 必须先重置暂存区：上一回合的终局若不清掉，投影器会把新回合的流式正文当成「已结束回合」而不显示
        chatState.beginTurn(null);
        TuiReActListener listener = new TuiReActListener(chatState.getInflight());
        try {
            ReActTurn turn = harness.chat(sessionId, text, listener);
            chatState.bindTurn(turn);
        } catch (JellyfishException e) {
            LOG.warn("TUI 回合启动失败：{}", e.getMessage());
            chatState.getInflight().finish(InflightTurn.Outcome.ERROR, e.getMessage());
        }
    }

    /**
     * 取当前会话标识。
     *
     * @return 当前会话标识
     * @throws JellyfishException 没有当前会话时抛出（说明启动期接线被绕过，属程序缺陷）
     */
    private String currentSessionId() {
        Session session = sessions.current();
        if (session == null) {
            throw new JellyfishException("当前没有会话：启动期未建立会话（外壳接线错误）");
        }
        return session.getSessionId();
    }

    /**
     * 取当前模型的上下文长度。
     *
     * @param session 当前会话，可为 {@code null}
     * @return 上下文长度；取不到时返回 0
     */
    private int contextLengthOf(Session session) {
        if (session == null || session.getProvider() == null || session.getModel() == null) {
            return 0;
        }
        try {
            ResolvedModel resolved = models.resolve(session.getProvider(), session.getModel());
            return resolved == null || resolved.getModel() == null ? 0 : resolved.getModel().getContextLength();
        } catch (JellyfishException e) {
            LOG.debug("状态栏取上下文长度失败：{}", e.getMessage());
            return 0;
        }
    }

    /**
     * 生成消息区标题。
     *
     * @param session 当前会话，可为 {@code null}
     * @return 标题文本，保证非 {@code null}
     */
    private static String title(Session session) {
        if (session == null) {
            return " jellyfish ";
        }
        String label = session.getTitle();
        if (label == null || label.isEmpty()) {
            label = session.getSessionId();
        }
        return " jellyfish \u00b7 " + label + " ";
    }

    /**
     * 输入框的按键截胡：在输入框处理之前把外壳关心的键拿下来。
     * <p>
     * <b>为什么不靠全局处理器</b>：实测它排在聚焦元素之后，而输入框会吃掉字符与 {@code Enter}，
     * 全局处理器只能看到没人要的键（例如 {@code ESCAPE}）。因此发送与中断必须挂在
     * {@link ChatInputView} 上，它在输入框<b>之前</b>拿到按键。
     * <p>
     * <b>Enter 为什么不在拦截名单里</b>：T5 采用<b>反转键位</b>——{@code Enter} 换行、
     * {@code Ctrl+S} 发送。原因是框架的键盘解码器不解析任何修饰键编码（见
     * {@link InputKeyMapper} 类注释），{@code Shift+Enter} / {@code Alt+Enter} 一律落成
     * {@code UNKNOWN}，所以「{@code Enter} 发送 + 修饰键换行」在<b>所有</b>终端上都不可实现。
     * 反转之后 {@code Enter} 结果落到 {@link InputAction#EDIT}，交给输入框当换行。
     * <p>
     * 滚动键也放这里而不是全局处理器：{@code PageUp} 可能被输入框内部滚动消费掉，
     * 放在截胡位置才能保证「消息区滚动」优先于「输入框内部滚动」。焦点常驻输入框（T8.6），
     * 消息区不是可聚焦元素，所以这是它唯一的入口。
     */
    private final class InputKeys implements KeyEventHandler {

        @Override
        public EventResult handle(KeyEvent key) {
            switch (InputKeyMapper.map(key)) {
                case SEND:
                    submit();
                    return EventResult.HANDLED;
                case CANCEL:
                    chatState.cancelTurn();
                    return EventResult.HANDLED;
                case QUIT:
                    quit();
                    return EventResult.HANDLED;
                case PAGE_UP:
                    chatState.pageUp();
                    return EventResult.HANDLED;
                case PAGE_DOWN:
                    chatState.pageDown();
                    return EventResult.HANDLED;
                case TO_BOTTOM:
                    chatState.toBottom();
                    return EventResult.HANDLED;
                default:
                    // EDIT：交给输入框，不在这里处理键位细节
                    return EventResult.UNHANDLED;
            }
        }
    }

    /**
     * 滚动键的兼底处理：只处理没人要的滚动键，不碰发送与中断。
     */
    private final class ScrollFallback implements GlobalEventHandler {

        @Override
        public EventResult handle(Event event) {
            if (!(event instanceof KeyEvent)) {
                return EventResult.UNHANDLED;
            }
            switch (InputKeyMapper.map((KeyEvent) event)) {
                case PAGE_UP:
                    chatState.pageUp();
                    return EventResult.HANDLED;
                case PAGE_DOWN:
                    chatState.pageDown();
                    return EventResult.HANDLED;
                case TO_BOTTOM:
                    chatState.toBottom();
                    return EventResult.HANDLED;
                default:
                    return EventResult.UNHANDLED;
            }
        }
    }
}
