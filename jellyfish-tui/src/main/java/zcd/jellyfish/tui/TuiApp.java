package zcd.jellyfish.tui;

import dev.tamboui.layout.Size;
import dev.tamboui.toolkit.app.ToolkitApp;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.toolkit.event.GlobalEventHandler;
import dev.tamboui.toolkit.event.KeyEventHandler;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CommandChoice;
import zcd.jellyfish.api.extension.CommandDescriptor;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.PermissionMode;
import zcd.jellyfish.api.ui.UiRegion;
import zcd.jellyfish.core.AgentHarness;
import zcd.jellyfish.core.ReActTurn;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.command.CommandInfo;
import zcd.jellyfish.infra.command.CommandManager;
import zcd.jellyfish.infra.llm.LlmUsage;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.model.ResolvedModel;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.infra.session.SessionMessage;
import zcd.jellyfish.infra.ui.OwnedPanel;
import zcd.jellyfish.infra.ui.UiContributions;
import zcd.jellyfish.infra.ui.UiSnapshot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * TUI 外壳：交互式终端界面的组装与事件循环。
 * <p>
 * <b>它只做四件事</b>：把按键翻译成动作、把动作交给内核、把内核的产出投影到屏幕、
 * 把会话运行态与插件贡献贴到状态栏与各面板区域。
 * 任何「思考」都不在这里——智能入口只有 {@link AgentHarness#chat}，命令入口只有 {@link CommandManager}，
 * 与 {@code CliRunMode} 走的是同一对门面，这条口径从 CLI 轮延续下来，不另起一套。
 * <p>
 * <b>插件界面内容靠「失效时收集」而不是「每帧收集」</b>：{@link UiContributions} 只在缓存失效时
 * 被问一次（空闲时零调用，见 {@link UiCache}）。代价是<b>失效触发源必须记全</b>——漏一个就是
 * 插件内容永久陈旧，因此五处都在本类里显式置位：会话切换（{@link #syncSession}）、
 * 回合开始（{@link #startTurn}）、回合收敛（{@link #render} 里比对上一帧的「进行中」状态）、
 * 命令执行后（{@link #executeCommand}），以及插件主动发布的失效事件与插件加载卸载
 * （{@link #onStart} 里订阅）。首帧由缓存初值保证。
 * <p>
 * <b>两条必须原样复用的规则</b>：
 * <ol>
 *     <li><b>分流判据只有 {@code CommandManager.isCommand}</b>——它只做语法判定、不查注册表，
 *     因此插件在启动后注册的命令也能被同一路径命中；</li>
 *     <li><b>每轮现读当前会话</b>（{@link SessionManager#current()}），不缓存 sessionId，
 *     这样 {@code /new}、{@code /resume} 之后立刻生效，界面也会跟着切到新会话的内容。</li>
 * </ol>
 * <p>
 * <b>首页与会话页是同一个界面的两种投影</b>：裸 {@code -tui} 启动时没有当前会话，消息区显示
 * {@link HomeSplash} 字标（首页）；用户真正发起对话或执行命令时才建会话（见
 * {@link #SESSION_DOMAIN_COMMANDS}），界面随之变成会话投影。因为视图本来就是「会话的投影」，
 * 两种状态共用同一条渲染路径（{@link ChatState} 按 {@code sessionId} 是否为 {@code null} 分支），
 * 不需要第二套界面。壳内唯一的硬约束是「不要假设当前会话一定存在」——
 * 补全、候选查询、命令分发都可能在首页发生。
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

    /** agent 门面，仅用于首页（无会话）时状态栏展示默认 agent。 */
    private final AgentManager agents;

    /** 插件 UI 贡献门面：收集插件贡献的界面内容，并订阅「可能已过期」。 */
    private final UiContributions uiContributions;

    /** 视图状态。 */
    private final ChatState chatState = new ChatState();

    /**
     * 面板落位状态：哪个插件的面板显示在哪个区域。
     * <p>
     * 归外壳而不是插件：区域是布局能力，也是多插件之间的竞争资源（一块区域同时只能显示一个），
     * 因此必须由外壳与用户（{@code /ui}）仲裁。刻意不持久化，重启回默认。
     */
    private final UiPlacement uiPlacement = new UiPlacement();

    /** 插件 UI 贡献的帧间缓存：只在失效时收集，空闲时零调用。 */
    private final UiCache uiCache;

    /** 命令补全状态：只由渲染线程读写。 */
    private final CommandCompletion completion = new CommandCompletion();

    /** 二级选择页状态：只由渲染线程读写。 */
    private final CommandChoicePicker picker = new CommandChoicePicker();

    /** 外壳自有命令在补全清单里的条目：命令名不含前缀，与命令域清单同构。 */
    private static final CommandInfo EXIT_INFO = new CommandInfo(ShellCommand.EXIT_NAME,
            new CommandDescriptor("退出 jellyfish", null, null));

    /** {@code /ui} 在补全清单里的条目。 */
    private static final CommandInfo UI_INFO = new CommandInfo(UiCommand.NAME,
            new CommandDescriptor("查看与切换插件的界面贡献", null, null));

    /**
     * 「会话域命令」：在首页上执行时<b>不</b>先建当前会话的那些命令。
     * <p>
     * 只有这几类不属于「先建一个新会话再执行」语义：{@code /new} 自己就会建会话，
     * {@code /resume} 是切到<b>已有</b>会话，{@code /delete} 是删掉某个会话。尤其是 {@code /delete}：
     * 若它也在首页先建一个空会话，就会变成「删了一个、又造了一个」，净效果为零，
     * 正好与用户要的清理目标相反；{@code /new} 若先建一个，一次会多出一条空会话。
     * 其余命令（含 {@code /session}）一律先建会话再执行——这是用户裁决的口径，见 tui方案.md。
     * <p>
     * 命令名与别名镜像 {@code core/command/SystemCommands} 的注册。与 {@link ShellCommand}
     * 硬编码外壳命令名是同一类取舍：外壳需要知道少数几条命令的语义来分流。
     */
    private static final Set<String> SESSION_DOMAIN_COMMANDS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("new", "resume", "delete", "rm")));

    /**
     * 插件 UI 的逃生门系统属性。
     * <p>
     * 置为 {@code false} 时整体关闭插件界面内容（状态栏片段与面板都不显示，也不再向插件收集）：
     * 面板会占掉消息区的地盘，不能接受时应当有一个开关退回「纯内核界面」。
     */
    static final String PLUGIN_PANELS_PROPERTY = "jellyfish.tui.pluginPanels";

    /** 是否启用插件 UI 贡献，构造期读一次（与鼠标捕获同口径：运行期改属性不影响已建的界面）。 */
    private final boolean pluginPanelsEnabled;

    /**
     * 鼠标捕获的逃生门系统属性。
     * <p>
     * 置为 {@code false} 时退回「不捕获鼠标」的旧行为：滚轮不再能滚消息区，
     * 但终端本地的鼠标选中/复制不再被打断（见 {@link #configure()}）。
     */
    static final String MOUSE_CAPTURE_PROPERTY = "jellyfish.tui.mouseCapture";

    /** 输入区视图。 */
    private final ChatInputView input;

    /** 版式。 */
    private final ChatShell shell;

    /** 输入框的按键截胡处理器：输入元素是聚焦元素，按键必经它，因此发送与中断都挂在这里。 */
    private final InputKeys inputKeys = new InputKeys();

    /** 上一帧显示的会话标识，用于识别会话切换：清掉旧会话的提示并按需重新贴出启动提示。 */
    private String renderedSessionId;

    /** 上一帧是否处于「回合进行中」，用于识别回合收敛并补一次插件内容失效。 */
    private boolean renderedTurnRunning;

    /**
     * 构造 TUI 外壳。
     *
     * @param harness  智能入口，不可为 {@code null}
     * @param commands 命令域服务，不可为 {@code null}
     * @param sessions 会话域服务，不可为 {@code null}
     * @param models   模型门面，不可为 {@code null}
     * @param agents   agent 门面，不可为 {@code null}
     * @param uiContributions 插件 UI 贡献门面，不可为 {@code null}
     */
    public TuiApp(AgentHarness harness, CommandManager commands, SessionManager sessions, ModelManager models,
                  AgentManager agents, UiContributions uiContributions) {
        this.harness = Objects.requireNonNull(harness, "harness must not be null");
        this.commands = Objects.requireNonNull(commands, "commands must not be null");
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.models = Objects.requireNonNull(models, "models must not be null");
        this.agents = Objects.requireNonNull(agents, "agents must not be null");
        this.uiContributions = Objects.requireNonNull(uiContributions, "uiContributions must not be null");
        this.uiCache = new UiCache(uiContributions);
        this.pluginPanelsEnabled = pluginPanelsEnabled();
        this.input = new ChatInputView(inputKeys);
        this.shell = new ChatShell(input);
    }

    /**
     * 判断本进程是否启用插件 UI 贡献。
     * <p>
     * 只有显式置为 {@code false} 才关闭：读取失败（未设置）时保持开启，
     * 因为「插件能往界面上放东西」是默认能力，逃生门是例外而非常态。
     *
     * @return 启用返回 {@code true}
     */
    static boolean pluginPanelsEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(PLUGIN_PANELS_PROPERTY));
    }

    /**
     * 调整终端能力开关：括号粘贴与鼠标捕获都开，鼠标移动仍关。
     * <p>
     * <b>括号粘贴必须开</b>：{@code bracketedPaste} 默认 {@code false}，此时终端不把粘贴内容包起来，
     * 而 {@code \r} 与 {@code \n} 都被解码成裸 {@code ENTER}——粘贴一段多行文本
     * 会把每个换行变成一次操作，等于把一次粘贴拆成多条消息。开启后
     * {@code PasteEvent} 一次送达完整文本，换行不再被当按键。
     * <p>
     * <b>鼠标捕获为什么开</b>：滚轮事件属于鼠标捕获，关着就<b>根本到不了应用</b>
     * （未捕获时不少终端会把备用屏下的滚轮翻译成 {@code ↑}/{@code ↓}，而这两个键归补全导航，
     * 面板没弹时落回输入框，在单行输入上表现为「滚轮毫无反应」）。开启后滚轮由
     * {@link MouseScrollMapper} 认领，点击 / 拖动等其它鼠标事件原样放行。
     * <p>
     * <b>代价与逃生门</b>：捕获开启后终端把鼠标交给应用，屏幕文本的本地选中/复制必须按住修饰键
     * （macOS 为 Option）。不能接受这个代价时用 {@code -D}{@link #MOUSE_CAPTURE_PROPERTY}{@code =false}
     * 退回旧行为，消息区滚动仍可用 {@code PageUp} / {@code PageDown} / {@code End}。
     * <p>
     * <b>为什么 {@code mouseMotion} 仍为 {@code false}</b>：本轮没有任何「悬停 / 拖动」语义，
     * 开启只会让终端把每一次指针移动都发过来；滚轮与按键事件不依赖它。
     *
     * @return 终端配置
     */
    @Override
    protected TuiConfig configure() {
        return TuiConfig.builder()
                .bracketedPaste(true)
                .mouseCapture(mouseCaptureEnabled())
                .build();
    }

    /**
     * 判断本进程是否开启鼠标捕获。
     * <p>
     * 只有显式置为 {@code false} 才关闭：读取失败（未设置）时保持开启，
     * 因为滚轮滚动是默认行为，逃生门是例外而非常态。
     *
     * @return 开启返回 {@code true}
     */
    static boolean mouseCaptureEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(MOUSE_CAPTURE_PROPERTY));
    }

    @Override
    protected void onStart() {
        // 兼容兼底的全局处理器：只在滚动键没被输入框吃掉时才会收到（焦点丢失等边界情况）。
        // 发送与中断不依赖它——实测全局处理器排在聚焦元素之后，靠它接不到 Enter。
        runner().eventRouter().addGlobalHandler(new ScrollFallback());
        // 插件改了内容它会主动说一声；回调在事件通道的订阅者线程，因此只置标记不做别的
        uiContributions.onInvalidated(uiCache::invalidate);
    }

    @Override
    protected Element render() {
        Session session = sessions.current();
        Size size = runner().tuiRunner().terminal().size();
        String sessionId = session == null ? null : session.getSessionId();
        // 每帧只取一次消息快照：Session.getMessages() 返回防御性副本，重复调用会白白多分配一份
        List<SessionMessage> messages = session == null ? null : session.getMessages();
        syncSession(sessionId);
        // 回合从「进行中」变为「已收敛」是插件内容最容易过期的时刻（工具刚改完状态），在这里补一次失效。
        // 终局判定放在渲染线程，因此能覆盖完成 / 报错 / 取消全部收敛路径，不必让三个回调各发一遍。
        boolean turnRunning = chatState.isTurnRunning();
        if (renderedTurnRunning && !turnRunning) {
            uiCache.invalidate();
        }
        renderedTurnRunning = turnRunning;

        // 一帧只收集一次，片段与面板共用同一份快照（缓存命中，不会重复问插件）
        UiSnapshot snapshot = pluginPanelsEnabled ? uiCache.snapshot(sessionId) : UiSnapshot.empty();
        List<String> fragments = uiPlacement.isHidden(UiRegion.STATUS)
                ? Collections.<String>emptyList()
                : snapshot.getStatusFragments();

        // 消息区宽度只取决于侧栏，与底部高度无关：先算它，才能把浮层面板按正确宽度折行
        Map<UiRegion, OwnedPanel> declared = uiPlacement.selected(snapshot.getPanels());
        int width = ChatLayout.messageWidth(size.width(), declared);
        // 补全每帧现算：命令清单不缓存（插件热部署后立刻可见），输入文本随按键而变
        completion.refresh(input.text(), availableCommands());
        Overlay overlay = buildOverlay(width);
        // 模态浮层打开时面板让位（只影响本帧显示，落位与缓存都不动）
        Map<UiRegion, OwnedPanel> panels = ChatShell.visiblePanels(declared, overlay);
        ChatLayout layout = ChatLayout.compute(size.width(), size.height(), input.panelRows(),
                ChatShell.overlayRows(overlay), panels);

        ChatState.View view = chatState.view(sessionId, messages, layout.getMessageWidth(),
                layout.getMessageRows(), TranscriptProjector.DEFAULT_MAX_MESSAGES);

        String status = StatusBarView.render(statusInfoOf(session, contextTokensOf(messages)));
        // 插件片段紧跟内核字段：宽度预算按终端总列数算，最后一个装不下的片段整块丢弃
        status = StatusBarView.appendFragments(status, fragments, size.width());
        String hint = view.hiddenBelowHint();
        if (hint != null) {
            // 提示放在状态栏而不是消息区：消息区的行数已被投影切片占满，
            // 额外加一行会把最新的一行挤出可视区——而「跟随底部」时用户最想看的正是那一行
            status = status + "   " + hint;
        }
        return shell.render(view, title(session), status, overlay, panels, layout);
    }

    /**
     * 生成本帧的浮层面板。
     * <p>
     * 二级选择页优先于补全面板：选择页是「命令已经发出、正在挑参数」的模态交互，
     * 而补全面板是「还没发出」的输入辅助，两者不应同屏。
     *
     * @param width 面板可用列数
     * @return 浮层；两者都没内容时返回空浮层
     */
    private Overlay buildOverlay(int width) {
        if (picker.isActive()) {
            return new Overlay(" " + picker.getBaseCommand() + " ",
                    CommandChoicePickerView.render(picker, width));
        }
        return ChatShell.completionOverlay(CommandCompletionView.render(completion, width));
    }

    /**
     * 取全部可用命令，供补全过滤。
     * <p>
     * 在外壳自有命令之外追加一条 {@code /exit}：它不进内核命令注册表（见 {@link ShellCommand}），
     * 但用户敲补全时应该看得到它。追加后重排序，保持清单整体按命令名升序。
     *
     * @return 命令清单；读取失败时返回空列表（补全不可用不应影响输入）
     */
    private List<CommandInfo> availableCommands() {
        try {
            List<CommandInfo> infos = new ArrayList<CommandInfo>(commands.commands());
            infos.add(EXIT_INFO);
            infos.add(UI_INFO);
            infos.sort(Comparator.comparing(CommandInfo::getName));
            return infos;
        } catch (RuntimeException e) {
            LOG.warn("读取命令清单失败，补全本次不可用：{}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 查询一条命令的只读候选（不执行命令）。
     *
     * @param commandName 命令名
     * @return 候选清单；查询失败或无候选时为空列表
     */
    private List<CommandChoice> optionsOf(String commandName) {
        try {
            return commands.options(commandName, currentSessionIdOrNull());
        } catch (RuntimeException e) {
            LOG.warn("查询命令候选失败：{}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 识别会话切换：清掉属于旧会话的外壳提示，并让插件界面内容重新收集一遍。
     * <p>
     * 提示是「外壳刚刚说过的话」，属于上一次交互的上下文；换了会话还留着，会让用户以为那是新会话的一部分。
     * 消息区本身由投影自动跟随，不需要在这里做任何事——这正是「视图 = 会话投影」的好处。
     * <p>
     * <b>首页 ↔ 会话页也是「切换」</b>：从首页建出第一个会话时 {@code sessionId} 由 {@code null} 变为新标识，
     * 或者删除当前会话后由标识变回 {@code null}，都会命中这里并清掉旧提示。
     *
     * @param sessionId 当前会话标识，可为 {@code null}（首页）
     */
    private void syncSession(String sessionId) {
        if (!Objects.equals(renderedSessionId, sessionId)) {
            chatState.clearNotices();
            renderedSessionId = sessionId;
            // 插件贡献是按会话给的，换了会话必须重新问一遍（UiCache 自己也会比对 sessionId，这里是双保险）
            uiCache.invalidate();
        }
    }

    /**
     * 处理用户提交。
     * <p>
     * <b>首页上的分流规则</b>：普通文本与绝大多数命令都先建一个当前会话再执行
     * （见 {@link #SESSION_DOMAIN_COMMANDS} 说明例外），{@code /exit} {@code /ui} 是外壳自有命令，
     * 不建会话。
     */
    private void submit() {
        if (input.isBlank()) {
            return;
        }
        if (chatState.isTurnRunning()) {
            chatState.appendNotice("回合进行中：按 Esc 可中断。", ShellNotice.Kind.INFO);
            return;
        }
        String text = input.takeText();
        // 外壳自有命令必须先截胡：交给命令域只会得到 UNKNOWN，而外壳其实完全听得懂
        if (ShellCommand.isShellCommand(text)) {
            if (UiCommand.isUi(text)) {
                executeUi(text);
            } else {
                quit();
            }
            return;
        }
        String sessionId = currentSessionIdOrNull();
        if (sessionId == null && !isSessionDomainCommand(text)) {
            // 首页 + 非会话域命令：先建会话再执行，界面随之进入会话页
            sessionId = createSession();
        }
        if (commands.isCommand(text)) {
            executeCommand(text, sessionId);
            return;
        }
        if (sessionId == null) {
            // 理论不可达：非命令文本不会命中会话域例外，上面一定已经建过会话
            chatState.appendNotice("当前没有会话，可用 /new 新建。", ShellNotice.Kind.ERROR);
            return;
        }
        startTurn(text, sessionId);
    }

    /**
     * 执行一条命令：带候选时打开二级选择页，否则把结果作为外壳提示贴上屏幕。
     *
     * @param text      命令原文
     * @param sessionId 当前会话标识
     */
    private void executeCommand(String text, String sessionId) {
        try {
            CommandResult result = commands.execute(text, sessionId);
            // 命令可能改了当前会话（/new /resume /delete）：先把会话切换的副作用落实（清掉旧会话的提示、
            // 重收集插件贡献），再把本次结果贴上去。否则下一帧 syncSession 会把刚贴的命令结果
            // 当成「旧会话留下的提示」一并清掉，用户看不到任何反馈。
            syncSession(currentSessionIdOrNull());
            if (result.hasChoices()) {
                // 命令要求挑一个取值：打开二级选择页，不再把列表文本重复贴到屏幕上
                picker.open(text.trim(), result.getChoices());
                return;
            }
            chatState.appendNotice(text, withShellUsage(text, result), kindOf(result.getKind()));
        } catch (JellyfishException e) {
            LOG.warn("TUI 命令执行失败：{}", e.getMessage());
            chatState.appendNotice(text, "命令执行失败：" + e.getMessage(), ShellNotice.Kind.ERROR);
        } finally {
            // 命令的副作用写在各自的域服务里，插件可能因此改了自家状态：这是外壳能看到的兜底失效点之一
            uiCache.invalidate();
        }
    }

    /**
     * 执行一条 {@code /ui} 命令。
     * <p>
     * 与其它命令一样贴成外壳提示，但<b>不走 {@link CommandManager}</b>：区域是外壳概念，
     * 内核命令域里没有它。
     * <p>
     * 面板候选取自当前缓存快照（可能为空——插件没贡献、或本进程已用逃生门关闭插件 UI）。
     * 用户敲错区域名会得到一条错误提示，而不是「未知命令」。
     *
     * @param text 命令原文
     */
    private void executeUi(String text) {
        UiCommand.Result result = UiCommand.execute(text, uiPlacement, currentPanels());
        chatState.appendNotice(text, result.getText(),
                result.isError() ? ShellNotice.Kind.ERROR : ShellNotice.Kind.INFO);
    }

    /**
     * 取当前会话的面板候选。
     * <p>
     * 只读缓存（不额外触发收集）：{@code /ui} 是用户主动敲的命令，界面刚渲染过，快照必然是最新的。
     *
     * @return 面板候选，保证非 {@code null}
     */
    private List<OwnedPanel> currentPanels() {
        Session session = sessions.current();
        String sessionId = session == null ? null : session.getSessionId();
        return uiCache.snapshot(sessionId).getPanels();
    }

    /**
     * 确认二级选择页的选中项：拼出「命令原文 + 取值」并执行。
     * <p>
     * 用原文而不是规范命令名拼接：别名同样能被命令域解析（{@code /a coder}），
     * 因此不需要在这里做一次名字解析。
     */
    private void confirmChoice() {
        CommandChoice choice = picker.selected();
        String baseCommand = picker.getBaseCommand();
        picker.dismiss();
        if (choice == null) {
            return;
        }
        String text = baseCommand + " " + choice.getValue();
        try {
            executeCommand(text, currentSessionIdOrNull());
        } catch (JellyfishException e) {
            LOG.warn("二级选择页执行失败：{}", e.getMessage());
            chatState.appendNotice(text, "命令执行失败：" + e.getMessage(), ShellNotice.Kind.ERROR);
        }
    }

    /**
     * 把命令结果状态映射成外壳提示语义。
     * <p>
     * 三态分开是为了「看得懂哪里出了错」：{@code UNKNOWN}（没有这条命令）不是失败，
     * 它只需要提醒用户看 {@code /help}，因此用警示色而不是错误色；
     * 若把它归到 {@code INFO}，敲错命令时的提示会和正常输出长得一模一样。
     *
     * @param kind 命令结果状态，不可为 {@code null}
     * @return 提示语义
     */
    private static ShellNotice.Kind kindOf(CommandResult.Kind kind) {
        switch (kind) {
            case ERROR:
                return ShellNotice.Kind.ERROR;
            case UNKNOWN:
                return ShellNotice.Kind.WARN;
            default:
                return ShellNotice.Kind.INFO;
        }
    }

    /**
     * 判断一条输入是不是「会话域命令」（在首页上不建会话）。
     * <p>
     * 只取第一个词并去掉前缀，不查注册表——与 {@link ShellCommand} 同口径：外壳只需要知道
     * {@link #SESSION_DOMAIN_COMMANDS} 这一小撮名字就能分流，「有没有这条命令」由命令域回答。
     *
     * @param text 用户输入原文
     * @return 是会话域命令返回 {@code true}
     */
    static boolean isSessionDomainCommand(String text) {
        String token = firstToken(text);
        return token != null && SESSION_DOMAIN_COMMANDS.contains(token);
    }

    /**
     * 判断一条输入是不是「无参的 /help」。
     *
     * @param text 用户输入原文
     * @return 是无参 /help 返回 {@code true}
     */
    static boolean isBareHelp(String text) {
        String token = firstToken(text);
        if (token == null || !("help".equals(token) || "h".equals(token) || "?".equals(token))) {
            return false;
        }
        return text.trim().indexOf(' ') < 0;
    }

    /**
     * 取输入里去掉前缀后的命令名。
     * <p>
     * <b>不是命令就返回 {@code null}</b>：两个调用方（首页分流与 {@code /help} 追加）都只在
     * 「输入确实是一条命令」时才该命中。若允许无前缀的词通过，用户把 {@code resume this} 当普通对话
     * 发出来时就会被当成命令、跳过建会话——这是首页上最容易踩的一个坑。
     *
     * @param text 用户输入原文，可为 {@code null}
     * @return 命令名（不含 {@code /}）；不是命令时为 {@code null}
     */
    private static String firstToken(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith(CommandManager.COMMAND_PREFIX)) {
            return null;
        }
        int end = 0;
        while (end < trimmed.length() && !Character.isWhitespace(trimmed.charAt(end))) {
            end++;
        }
        return trimmed.substring(CommandManager.COMMAND_PREFIX.length(), end);
    }

    /**
     * 给无参 {@code /help} 的输出追加外壳侧用法说明。
     * <p>
     * <b>为什么在外壳侧追加而不写进内核帮助</b>：说明里是 TUI 专属键位（{@code Ctrl+S} / 滚轮），
     * 写进内核会让 {@code -cli} / {@code -server} 的 {@code /help} 冒出按不了的键位。
     * 只对无参 {@code /help} 追加：{@code /help /model} 是查单条命令，再附一段全局键位只是噪音。
     *
     * @param text   命令原文
     * @param result 命令结果
     * @return 要展示的输出文本，保证非 {@code null}
     */
    static String withShellUsage(String text, CommandResult result) {
        String output = result.getOutput() == null ? "" : result.getOutput();
        if (!isBareHelp(text)) {
            return output;
        }
        return output.isEmpty() ? ShellUsage.text() : output + "\n\n" + ShellUsage.text();
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
        // 回合开始是插件内容可能变化的起点（例如模型马上要改待办），先让下一帧重问一遍
        uiCache.invalidate();
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
     * 取当前会话标识，没有当前会话时返回 {@code null}。
     * <p>
     * TUI 现在从首页（无会话）进入，因此「没有当前会话」是合法状态而不是接线错误：
     * 首页上补全候选查询、二级选择页确认、命令分发都可能在没有会话时发生。
     * 需要会话的路径（发起回合）必须先经 {@link #createSession()} 建会话。
     *
     * @return 当前会话标识，没有当前会话时为 {@code null}
     */
    private String currentSessionIdOrNull() {
        Session session = sessions.current();
        return session == null ? null : session.getSessionId();
    }

    /**
     * 在首页建一个新会话并切为当前。
     * <p>
     * 这是「首页 → 会话页」的唯一入口：用户真正要发起对话或执行命令时才建会话，
     * 因此「进来看看」不会留下空会话文件——会话持久化是 {@code create} 的一等职责，建了就一定落盘。
     *
     * @return 新会话的标识，保证非 {@code null}
     */
    private String createSession() {
        Session session = sessions.createDefault();
        sessions.switchTo(session.getSessionId());
        return session.getSessionId();
    }

    /**
     * 装配状态栏数据。
     * <p>
     * 每帧现读会话，并把「生效模型」解析交给 {@link ModelManager}：会话未显式指定 provider / model
     * 时显示的是配置默认值解析出的真实模型，而不是「默认」两个字——用户关心的是实际在跑哪个模型。
     * 解析失败只退回会话原始字段（可能为 {@code null}），不让状态栏因配置问题而整行消失。
     *
     * @param session       当前会话，可为 {@code null}（首页）
     * @param contextTokens 最近一次调用的输入 token 数
     * @return 状态栏数据，保证非 {@code null}
     */
    private StatusBarView.Info statusInfoOf(Session session, long contextTokens) {
        if (session == null) {
            return homeStatusInfo();
        }
        ResolvedModel resolved = resolvedModel(session);
        String provider = resolved == null ? session.getProvider() : resolved.getProviderName();
        String model = resolved == null ? session.getModel() : resolved.getModelName();
        int contextLength = resolved == null ? 0 : resolved.getModel().getContextLength();
        return new StatusBarView.Info(session.getAgentId(), provider, model, session.getPermissionMode(),
                System.getProperty("user.dir"), contextTokens, contextLength, session.getUsage());
    }

    /**
     * 装配首页（无会话）状态栏数据：展示「将要使用的」默认 agent 与默认模型。
     * <p>
     * 首页没有会话，但状态栏也不应该是一片空白：用户正要看的就是「现在如果用，会用哪个 agent 与模型」，
     * 因此这里解析配置默认值而不是显示 {@code -}。权限模式取 {@link PermissionMode#NORMAL}，
     * 与 {@code SessionManager.createDefault()} 建的会话一致，保证「首页看到的」和「建出来的」相同。
     * <p>
     * 解析失败（没配模型）只退回 {@code null}，让状态栏退回「默认」字样，不因配置问题整行消失。
     *
     * @return 状态栏数据，保证非 {@code null}
     */
    private StatusBarView.Info homeStatusInfo() {
        ResolvedModel resolved = null;
        try {
            resolved = models.resolveDefault();
        } catch (JellyfishException e) {
            LOG.debug("首页解析默认模型失败：{}", e.getMessage());
        }
        String provider = resolved == null ? null : resolved.getProviderName();
        String model = resolved == null ? null : resolved.getModelName();
        int contextLength = resolved == null ? 0 : resolved.getModel().getContextLength();
        return new StatusBarView.Info(defaultAgentId(), provider, model, PermissionMode.NORMAL,
                System.getProperty("user.dir"), 0L, contextLength, null);
    }

    /**
     * 取配置里的默认 agent 标识。
     *
     * @return 默认 agentId；没有配置时返回 {@code null}
     */
    private String defaultAgentId() {
        try {
            return agents.getDefaultAgentId();
        } catch (RuntimeException e) {
            LOG.debug("首页读取默认 agent 失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析会话当前生效的模型：显式配置了 provider 与 model 就精确解析，否则跟随配置默认值。
     *
     * @param session 会话运行态，不可为 {@code null}
     * @return 解析结果；解析不到时返回 {@code null}
     */
    private ResolvedModel resolvedModel(Session session) {
        try {
            String provider = session.getProvider();
            String model = session.getModel();
            if (provider == null || provider.isEmpty() || model == null || model.isEmpty()) {
                return models.resolveDefault();
            }
            return models.resolve(provider, model);
        } catch (JellyfishException e) {
            LOG.debug("状态栏解析当前模型失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 取最近一次调用返回的输入 token 数，作为「当前上下文长度」。
     * <p>
     * <b>为什么不用累计总量</b>：累计值可以远超上下文窗口，用它当分子会让人误判「快爆了」。
     * 厂商在每次响应里返回的 prompt tokens 才是这一次真正送进模型的上下文规模（含系统提示词），
     * 而最后一条带用量的 assistant 消息正对应最近一次调用。
     * <p>
     * 从后往前找：越新的调用越接近「当前」，找到即返回，不必遍历整份历史。
     *
     * @param messages 消息快照，可为 {@code null}
     * @return 输入 token 数；尚无调用时返回 0
     */
    static long contextTokensOf(List<SessionMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0L;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            LlmUsage usage = messages.get(i).getUsage();
            if (usage != null) {
                return usage.getPromptTokens();
            }
        }
        return 0L;
    }

    /**
     * 生成消息区标题。
     *
     * @param session 当前会话，可为 {@code null}（首页）
     * @return 标题文本；首页（无会话）时为 {@code null}，表示不显示标题栏（字标已在内容里居中）
     */
    private static String title(Session session) {
        if (session == null) {
            return null;
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
     * <b>Enter 的双重含义</b>：T5 采用<b>反转键位</b>——面板没弹时 {@code Enter} 换行、
     * {@code Ctrl+S} 发送。原因是框架的键盘解码器不解析任何修饰键编码（见
     * {@link InputKeyMapper} 类注释），{@code Shift+Enter} / {@code Alt+Enter} 一律落成
     * {@code UNKNOWN}，所以「{@code Enter} 发送 + 修饰键换行」在<b>所有</b>终端上都不可实现。
     * 而补全面板可见时，外壳把这一个键截下来当「选中」；面板没弹时原样放行，
     * 由输入框当换行——两种含义靠「面板是否可见」区分，不需要修饰键。
     * <p>
     * 滚动键也放这里而不是全局处理器：{@code PageUp} 可能被输入框内部滚动消费掉，
     * 放在截胡位置才能保证「消息区滚动」优先于「输入框内部滚动」。焦点常驻输入框（T8.6），
     * 消息区不是可聚焦元素，所以这是它唯一的入口。
     * <p>
     * <b>滚轮不在这里</b>：鼠标事件根本不进元素路由的键盘路径，而是先到
     * {@link ScrollFallback}（{@code EventRouter} 对 {@code MouseEvent} 就是先跑全局处理器）。
     * 两条路径最终都进 {@link TuiApp#applyScroll}，因此滚动语义只有一份。
     */
    private final class InputKeys implements KeyEventHandler {

        @Override
        public EventResult handle(KeyEvent key) {
            InputAction action = InputKeyMapper.map(key);
            if (picker.isActive()) {
                return handlePicker(action);
            }
            if (action == InputAction.COMPLETE_PREV
                    || action == InputAction.COMPLETE_NEXT
                    || action == InputAction.COMPLETE_ACCEPT) {
                return handleCompletion(action);
            }
            if (applyScroll(action)) {
                return EventResult.HANDLED;
            }
            switch (action) {
                case SEND:
                    submit();
                    return EventResult.HANDLED;
                case CANCEL:
                    // 先收起补全面板再谈中断：无进行中回合时 cancelTurn 是空操作，两者可以共存
                    completion.dismiss();
                    chatState.cancelTurn();
                    return EventResult.HANDLED;
                case QUIT:
                    quit();
                    return EventResult.HANDLED;
                default:
                    // EDIT：交给输入框，不在这里处理键位细节
                    return EventResult.UNHANDLED;
            }
        }

        /**
         * 处理补全导航与接受。
         * <p>
         * <b>面板没弹时一律返回 {@code UNHANDLED}</b>：{@code ↑}/{@code ↓} 要落回输入框做光标移动，
         * 否则输入框里就再也无法上下移动光标了。
         *
         * @param action 补全动作
         * @return 处理结果
         */
        private EventResult handleCompletion(InputAction action) {
            completion.refresh(input.text(), availableCommands());
            // 无候选时一并不接管这三个键：否则光标会被「无匹配命令」的占位行锁住动不了
            if (!completion.isActive() || completion.getCandidates().isEmpty()) {
                return EventResult.UNHANDLED;
            }
            switch (action) {
                case COMPLETE_PREV:
                    completion.moveUp();
                    return EventResult.HANDLED;
                case COMPLETE_NEXT:
                    completion.moveDown();
                    return EventResult.HANDLED;
                case COMPLETE_ACCEPT:
                    return acceptCandidate();
                default:
                    return EventResult.UNHANDLED;
            }
        }

        /**
         * 确认补全面板的选中项。
         * <p>
         * <b>有候选 → 直接进二级选择页</b>（只读查询，不执行命令，因此不会误触发 {@code /new} 这类副作用）；
         * <b>无候选 → 保持原行为回填命令名</b>，用户接着敲参数或发送。
         *
         * @return 处理结果
         */
        private EventResult acceptCandidate() {
            CommandInfo candidate = completion.selected();
            if (candidate == null) {
                return EventResult.UNHANDLED;
            }
            List<CommandChoice> options = optionsOf(candidate.getName());
            if (!options.isEmpty()) {
                // 清掉已敲的命令词：选择页确认后会按「命令名 + 取值」重新执行，半截输入留着只会造成误解
                input.takeText();
                completion.dismiss();
                picker.open(CommandCompletion.PREFIX + candidate.getName(), options);
                return EventResult.HANDLED;
            }
            String accepted = completion.accept();
            if (accepted != null) {
                input.replaceText(accepted);
                // 接受后收起并记住新命令词：否则候选只剩刚选中的那一条，看起来像没生效
                completion.refresh(accepted, availableCommands());
                completion.dismiss();
            }
            return EventResult.HANDLED;
        }

        /**
         * 处理二级选择页的按键。
         * <p>
         * <b>选择页是模态的</b>（保持页面直到 {@code Esc}）：除导航、确认、取消与外壳级快捷键外，
         * 其余按键一律吞掉，页面不因输入框内容变化而关闭。
         *
         * @param action 按键动作
         * @return 处理结果
         */
        private EventResult handlePicker(InputAction action) {
            if (applyScroll(action)) {
                return EventResult.HANDLED;
            }
            switch (action) {
                case COMPLETE_PREV:
                    picker.moveUp();
                    return EventResult.HANDLED;
                case COMPLETE_NEXT:
                    picker.moveDown();
                    return EventResult.HANDLED;
                case COMPLETE_ACCEPT:
                    confirmChoice();
                    return EventResult.HANDLED;
                case CANCEL:
                    picker.dismiss();
                    return EventResult.HANDLED;
                case QUIT:
                    quit();
                    return EventResult.HANDLED;
                default:
                    // 其余键吞掉：选择页保持到 Esc，输入框不会在模态页面背后被改动
                    return EventResult.HANDLED;
            }
        }
    }

    /**
     * 滚动键与滚轮的兼底处理：只处理滚动事件，不碰发送与中断。
     * <p>
     * <b>鼠标事件必须先于元素路由到达这里</b>：实测 {@code EventRouter.route} 对 {@code MouseEvent}
     * 是先跑全局处理器、再从坐标找元素，因此滚轮不必挂在某个元素上（消息区本来也不是可聚焦元素）。
     * <p>
     * <b>非滚轮的鼠标事件为什么要吞掉而不是放行</b>：放行会落到框架的鼠标路由里，而它对「点在没有元素命中的位置」
     * 的处理是 {@code focusManager.clearFocus()}——点一下消息区就会丢掉输入框焦点，下一帧才被框架重新挑回。
     * 输入框是唯一的可聚焦元素，鼠标本来也做不了别的事，因此这里一律吞掉，把「焦点常驻输入框」（T8.6）
     * 变成确定性行为。将来若要支持点击元素，需要在这里放行非滚轮事件，并同时接受上述焦点语义。
     */
    private final class ScrollFallback implements GlobalEventHandler {

        @Override
        public EventResult handle(Event event) {
            if (event instanceof MouseEvent) {
                return handleMouse((MouseEvent) event);
            }
            if (event instanceof KeyEvent) {
                return applyScroll(InputKeyMapper.map((KeyEvent) event))
                        ? EventResult.HANDLED
                        : EventResult.UNHANDLED;
            }
            return EventResult.UNHANDLED;
        }

        /**
         * 处理鼠标事件：滚轮滚消息区，其余吞掉。
         *
         * @param event 鼠标事件
         * @return 处理结果
         */
        private EventResult handleMouse(MouseEvent event) {
            Optional<InputAction> action = MouseScrollMapper.map(event);
            if (action.isPresent()) {
                applyScroll(action.get());
            }
            return EventResult.HANDLED;
        }
    }

    /**
     * 应用一条滚动动作。
     * <p>
     * 键盘路径（{@link InputKeys} / {@link ScrollFallback}）与鼠标路径（{@link MouseScrollMapper}）
     * 共用这里，因此「翻页 / 滚轮 / 回底」的语义只有一份：全部落在 {@link ChatState} 的滚动状态上，
     * 跟随与回底的行为天然一致。
     *
     * @param action 按键或滚轮判定出的动作
     * @return 动作归滚动管返回 {@code true}；其余动作返回 {@code false} 且无副作用
     */
    private boolean applyScroll(InputAction action) {
        switch (action) {
            case PAGE_UP:
                chatState.pageUp();
                return true;
            case PAGE_DOWN:
                chatState.pageDown();
                return true;
            case SCROLL_UP:
                chatState.scrollUp(MouseScrollMapper.WHEEL_STEP_ROWS);
                return true;
            case SCROLL_DOWN:
                chatState.scrollDown(MouseScrollMapper.WHEEL_STEP_ROWS);
                return true;
            case TO_BOTTOM:
                chatState.toBottom();
                return true;
            default:
                return false;
        }
    }
}
