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
import zcd.jellyfish.core.compact.ConversationCompactor;
import zcd.jellyfish.core.input.InputDirectiveCall;
import zcd.jellyfish.core.input.InputDirectiveRun;
import zcd.jellyfish.core.input.InputDirectives;
import zcd.jellyfish.core.input.InputReferenceCompletion;
import zcd.jellyfish.core.ReActTurn;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.command.CommandInfo;
import zcd.jellyfish.infra.command.CommandManager;
import zcd.jellyfish.infra.llm.LlmUsage;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.model.ResolvedModel;
import zcd.jellyfish.infra.permission.ApprovalChannel;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionDefaults;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.infra.session.SessionMessage;
import zcd.jellyfish.infra.ui.OwnedPanel;
import zcd.jellyfish.infra.ui.UiContributions;
import zcd.jellyfish.infra.ui.UiSnapshot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
 * {@link HomeSplash} 字标（首页）；用户真正发起对话时才建会话，界面随之变成会话投影。因为视图本来就是
 * 「会话的投影」，两种状态共用同一条渲染路径（{@link ChatState} 按 {@code sessionId} 是否为 {@code null}
 * 分支），不需要第二套界面。壳内唯一的硬约束是「不要假设当前会话一定存在」——
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

    /**
     * 人工审批通道：本外壳是唯一的审批者（{@code -tui} 启动时挂上）。
     * <p>
     * 只在这里读、在这里答：{@code react} 线程在通道那头阻塞等待，本外壳每帧取件、按键回填。
     */
    private final ApprovalChannel approvals;

    /**
     * 会话压缩器：{@code /compact} 的执行体。
     * <p>
     * <b>只读状态，不驱动它</b>：压缩跑在 {@code compact} 线程上，本外壳每帧取一次状态
     * ——{@code RUNNING} 显示在状态栏，从 {@code RUNNING} 变为 {@code DONE}/{@code FAILED} 时贴一条提示。
     * 这与审批通道是同一类「把线程间的交接点放在渲染线程上」的形态，区别是审批要回填、压缩不用。
     */
    private final ConversationCompactor compactor;

    /** 输入指令服务：{@code !} / {@code @} 的解析、执行与补全全在内核，外壳只渲染与分流。 */
    private final InputDirectives inputDirectives;

    /**
     * 本进程内新建会话的待生效默认值。
     * <p>
     * 首页状态栏要读它：那里展示的是「现在如果用，会用哪个 agent 与模型」，而
     * {@code /model} {@code /agent} {@code /mode} 在首页改的就是这个值。不读它，用户改完会看到
     * 状态栏依旧显示配置默认值——展示与实际将要使用的值对不上。
     */
    private final SessionDefaults sessionDefaults;

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

    /** 行内引用补全状态：只由渲染线程读写。 */
    private final InputReferenceCompletionState referenceCompletion = new InputReferenceCompletionState();

    /** 上一次引用补全查询的键（原文 + 光标），用于免去无意义的重复插件调用。 */
    private String lastReferenceQueryKey;

    /** 上一次引用补全的查询结果，与 {@link #lastReferenceQueryKey} 成对使用。 */
    private InputReferenceCompletion lastReferenceCompletion = InputReferenceCompletion.empty();

    /** 二级选择页状态：只由渲染线程读写。 */
    private final CommandChoicePicker picker = new CommandChoicePicker();

    /**
     * 审批浮层的选项状态：与二级选择页共用同一套选择逻辑，但状态分开。
     * <p>
     * <b>为什么不与 {@link #picker} 复用同一个对象</b>：两者可能前后脚出现
     * （在二级选择页上确认的命令问出了需要审批的工具），共用对象会让后者的候选覆盖前者，
     * 用户回到选择页时看到的是审批的两个选项。
     */
    private final CommandChoicePicker approvalPicker = new CommandChoicePicker();

    /** 审批浮层当前承载的请求 id：换了请求就要重建选项，否则选中态会从上一个请求继承。 */
    private String renderedApprovalId;

    /** 外壳自有命令在补全清单里的条目：命令名不含前缀，与命令域清单同构。 */
    private static final CommandInfo EXIT_INFO = new CommandInfo(ShellCommand.EXIT_NAME,
            new CommandDescriptor("退出 jellyfish", null, null));

    /** {@code /ui} 在补全清单里的条目。 */
    private static final CommandInfo UI_INFO = new CommandInfo(UiCommand.NAME,
            new CommandDescriptor("查看与切换插件的界面贡献", null, null));

    /** {@code /thinking} 在补全清单里的条目。 */
    private static final CommandInfo THINKING_INFO = new CommandInfo(ShellCommand.THINKING_NAME,
            new CommandDescriptor("展开 / 折叠思考过程", null, null));

    /** {@code /mouse} 在补全清单里的条目。 */
    private static final CommandInfo MOUSE_INFO = new CommandInfo(MouseCommand.NAME,
            new CommandDescriptor("交还 / 收回鼠标（Ctrl+O 同效）", null, null));

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
     * 运行期做同一件事用 {@code /mouse} 或 {@code Ctrl+O}，不必重启进程。
     */
    static final String MOUSE_CAPTURE_PROPERTY = "jellyfish.tui.mouseCapture";

    /**
     * 恢复鼠标捕获时附带的「移动事件」开关。
     * <p>
     * 与 {@link #configure()} 用同一个取值：本项目没有任何悬停 / 拖动语义，开启只会让终端把每一次
     * 指针移动都发过来。抽成常量是为了让「启动」与「运行期收回」两条路径共用一个事实来源，
     * 不会出现一边开一边不开。
     */
    private static final boolean MOUSE_MOTION = false;

    /**
     * 当前是否处于鼠标捕获态。
     * <p>
     * 初值取自启动配置（{@link #mouseCaptureEnabled()}），由 {@code /mouse} 与 {@code Ctrl+O} 在运行期改写。
     * 框架只在<b>启动与关闭</b>两处按 {@code TuiConfig} 设置终端模式，不感知运行期的改动，
     * 因此这个偏移只能由本类自己记住——退出时若它与启动配置不一致，终端会带着鼠标上报模式回到 shell
     * （见 {@link #exitShell()}）。
     */
    private boolean mouseCaptured = mouseCaptureEnabled();

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

    /** 压缩在界面上的那一层：状态栏标记与「压完了」的一次性提示。 */
    private final CompactionView compactionView = new CompactionView();

    /**
     * 构造 TUI 外壳。
     *
     * @param harness  智能入口，不可为 {@code null}
     * @param commands 命令域服务，不可为 {@code null}
     * @param sessions 会话域服务，不可为 {@code null}
     * @param models   模型门面，不可为 {@code null}
     * @param agents   agent 门面，不可为 {@code null}
     * @param uiContributions 插件 UI 贡献门面，不可为 {@code null}
     * @param approvals 人工审批通道，不可为 {@code null}
     * @param compactor 会话压缩器，不可为 {@code null}
     * @param inputDirectives 输入指令服务，不可为 {@code null}
     * @param thinkingExpanded 启动时是否展开思考过程（{@code --show-thinking} 置为 {@code true}）
     * @param sessionDefaults 本进程内新建会话的待生效默认值，不可为 {@code null}
     */
    public TuiApp(AgentHarness harness, CommandManager commands, SessionManager sessions, ModelManager models,
                  AgentManager agents, UiContributions uiContributions, ApprovalChannel approvals,
                  ConversationCompactor compactor, InputDirectives inputDirectives, boolean thinkingExpanded,
                  SessionDefaults sessionDefaults) {
        this.harness = Objects.requireNonNull(harness, "harness must not be null");
        this.commands = Objects.requireNonNull(commands, "commands must not be null");
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.models = Objects.requireNonNull(models, "models must not be null");
        this.agents = Objects.requireNonNull(agents, "agents must not be null");
        this.uiContributions = Objects.requireNonNull(uiContributions, "uiContributions must not be null");
        this.approvals = Objects.requireNonNull(approvals, "approvals must not be null");
        this.compactor = Objects.requireNonNull(compactor, "compactor must not be null");
        this.inputDirectives = Objects.requireNonNull(inputDirectives, "inputDirectives must not be null");
        this.sessionDefaults = Objects.requireNonNull(sessionDefaults, "sessionDefaults must not be null");
        this.uiCache = new UiCache(uiContributions);
        this.pluginPanelsEnabled = pluginPanelsEnabled();
        this.input = new ChatInputView(inputKeys);
        this.shell = new ChatShell(input);
        chatState.setThinkingExpanded(thinkingExpanded);
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
     * <b>代价与两个逃生门</b>：捕获开启后终端把鼠标交给应用，屏幕文本的本地选中/复制必须按住修饰键
     * （macOS 为 Option，而 Terminal.app 上那是矩形选择，等于没有）。两种应对方式：运行期按
     * {@code Ctrl+O} 或 {@code /mouse} 把鼠标交还终端（见 {@link #setMouseCapture(boolean)}，
     * 用完再收回，滚轮只在这期间停用），或者用 {@code -D}{@link #MOUSE_CAPTURE_PROPERTY}{@code =false}
     * 整体退回旧行为（消息区滚动改用 {@code PageUp} / {@code PageDown} / {@code End}）。
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
                .mouseMotion(MOUSE_MOTION)
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
        syncReferenceCompletion();
        // 指令进度每帧对齐一次：执行线程只写暂存区，这里负责在它结束时收尾
        syncDirective();
        Overlay overlay = buildOverlay(width);
        // 模态浮层打开时面板让位（只影响本帧显示，落位与缓存都不动）
        Map<UiRegion, OwnedPanel> panels = ChatShell.visiblePanels(declared, overlay);
        ChatLayout layout = ChatLayout.compute(size.width(), size.height(), input.panelRows(),
                ChatShell.overlayRows(overlay), panels);

        ChatState.View view = chatState.view(sessionId, messages, layout.getMessageWidth(),
                layout.getMessageRows(), TranscriptProjector.DEFAULT_MAX_MESSAGES);

        String status = StatusBarView.render(statusInfoOf(session, contextTokensOf(messages)));
        // 压缩状态是「正在进行 / 已经压过一部分」的事实，模型与屏幕的差异必须有个出口
        status = status + syncCompaction(session, sessionId);
        // 交还鼠标同样是「持续有效」的状态：滚轮看起来没反应时，屏幕上必须有一处说明原因以及怎么收回
        status = status + MouseCommand.statusMarker(mouseCaptured);
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
     * 对表压缩状态：把状态栏标记返回给调用方，并在「压缩中 → 终态」时贴一条提示。
     * <p>
     * <b>为什么状态栏也要有一份</b>：提示贴进消息流后会随滚动移出视野，而「这个会话的历史已被压过」
     * 是一个<b>持续有效</b>的事实（模型确实看不到那些原文了），只贴一次提示不足以让人一直记得。
     *
     * @param session   当前会话，可为 {@code null}
     * @param sessionId 当前会话标识，可为 {@code null}
     * @return 接到状态栏尾部的标记文本，可能为空串
     */
    private String syncCompaction(Session session, String sessionId) {
        ConversationCompactor.State state = compactor.status(sessionId);
        CompactionView.Notice notice = compactionView.sync(sessionId, state);
        if (notice != null) {
            chatState.appendNotice("/compact", notice.getText(), notice.getKind());
        }
        return CompactionView.label(session, state);
    }

    /**
     * 生成本帧的浮层面板。
     * <p>
     * <b>审批浮层的优先级最高</b>：它背后有一条阻塞等待的 {@code react} 线程，而补全面板与二级选择页
     * 都只是输入辅助——用户不回答审批，那个回合就一直停着。因此只要通道里有待审批项，就只显示它
     * （选择页状态不清，审批结束后下一帧自然回到用户刚才的操作）。
     * <p>
     * 二级选择页优先于补全面板：选择页是「命令已经发出、正在挑参数」的模态交互，
     * 而补全面板是「还没发出」的输入辅助，两者不应同屏。
     *
     * @param width 面板可用列数
     * @return 浮层；都没有内容时返回空浮层
     */
    private Overlay buildOverlay(int width) {
        ApprovalChannel.Pending pending = approvals.pending().orElse(null);
        if (pending != null) {
            syncApproval(pending);
            return new Overlay(ApprovalPrompt.TITLE, ApprovalPrompt.render(pending, approvalPicker, width));
        }
        if (picker.isActive()) {
            return new Overlay(" " + picker.getBaseCommand() + " ",
                    CommandChoicePickerView.render(picker, width));
        }
        // 命令补全优先于引用补全：两者不会同时命中（一个片段不可能既是 /xxx 又是 @xxx），
        // 但顺序写死比依赖「不会同时发生」更稳，也不会让将来新增的标记抢了命令的位置
        Overlay commandOverlay = ChatShell.completionOverlay(CommandCompletionView.render(completion, width));
        if (!commandOverlay.isEmpty()) {
            return commandOverlay;
        }
        return ChatShell.referenceOverlay(InputReferenceCompletionView.render(referenceCompletion, width));
    }

    /**
     * 对齐行内引用补全：按当前输入与光标位置向内核要一次结果。
     * <p>
     * <b>为什么按「原文 + 光标」缓存一次</b>：渲染循环约 26fps 且输入往往一动不动，
     * 而补全查询会同步调用插件（可能在列举目录）。键没变就直接复用上一帧的结果，
     * 于是只有真正敲键或移光标时才会问插件一次。
     */
    private void syncReferenceCompletion() {
        String text = input.text();
        int cursor = input.cursor();
        String key = text + '\u0000' + cursor;
        if (!key.equals(lastReferenceQueryKey)) {
            lastReferenceQueryKey = key;
            lastReferenceCompletion = queryReferenceCompletion(text, cursor);
        }
        referenceCompletion.refresh(text, lastReferenceCompletion);
    }

    /**
     * 问内核要一次引用补全候选。
     *
     * @param text   输入框原文
     * @param cursor 光标字符偏移
     * @return 补全结果，保证非 {@code null}
     */
    private InputReferenceCompletion queryReferenceCompletion(String text, int cursor) {
        try {
            return inputDirectives.complete(text, cursor, currentSessionIdOrNull());
        } catch (RuntimeException e) {
            // 补全失败不该把界面弄崩：退化成「未命中」，用户还可以手敲路径
            LOG.warn("引用补全失败：{}", e.getMessage());
            return InputReferenceCompletion.empty();
        }
    }

    /**
     * 对齐输入指令的收尾。
     * <p>
     * 指令跑在 {@code input-directive} 线程上，渲染线程每帧轮询一次句柄（与压缩状态同一形态）：
     * 执行结束时把暂存区置为终态，下一帧就不再显示「运行中」。结果消息由内核自己落会话，
     * 因此这里不贴任何外壳提示——投影会自动把它显示出来。
     */
    private void syncDirective() {
        InputDirectiveRun run = chatState.getDirective();
        if (run == null || !run.isDone()) {
            return;
        }
        chatState.clearDirective();
        chatState.getInflight().finish(InflightTurn.Outcome.COMPLETED, null);
    }

    /**
     * 让审批选项跟上当前请求。
     * <p>
     * 只有换了请求（id 变了）才重建选项：它是「当前挂起的那一条」的状态，
     * 重建会把选中态重置到首项（「允许一次」），而同一请求的重复渲染必须保留用户已经用
     * {@code ↑}/{@code ↓} 做出的选择——每帧重建会让上下键看起来完全没用。
     *
     * @param pending 当前待审批请求
     */
    private void syncApproval(ApprovalChannel.Pending pending) {
        if (pending.getId().equals(renderedApprovalId)) {
            return;
        }
        renderedApprovalId = pending.getId();
        approvalPicker.dismiss();
        approvalPicker.open("", ApprovalPrompt.choices());
    }

    /**
     * 取当前上下文下可用的命令，供补全过滤。
     * <p>
     * <b>首页时滤掉需要会话的那些</b>：{@code /compact} 这类命令在首页上根本不是命令
     * （手敲会被当对话发给模型），列在补全面板里只会误导。判据来自命令自己的名片
     * （{@code CommandDescriptor.sessionRequired}），外壳不维护第二份名单。
     * <p>
     * 在外壳自有命令之外追加一条 {@code /exit}：它不进内核命令注册表（见 {@link ShellCommand}），
     * 但用户敲补全时应该看得到它。追加后重排序，保持清单整体按命令名升序。
     *
     * @return 命令清单；读取失败时返回空列表（补全不可用不应影响输入）
     */
    private List<CommandInfo> availableCommands() {
        try {
            boolean hasSession = currentSessionIdOrNull() != null;
            List<CommandInfo> infos = new ArrayList<CommandInfo>();
            for (CommandInfo info : commands.commands()) {
                if (hasSession || !info.isSessionRequired()) {
                    infos.add(info);
                }
            }
            // 外壳自有命令全都不依赖会话，永远列出来
            infos.add(EXIT_INFO);
            infos.add(UI_INFO);
            infos.add(THINKING_INFO);
            infos.add(MOUSE_INFO);
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
     * <b>首页上的分流完全交给命令域</b>：{@code CommandManager.shouldRunAsCommand} 回答
     * 「这条输入在当前上下文下该不该当命令」。外壳不再维护一份「哪些命令在首页不建会话」的名字表——
     * 那份知识归命令自己的名片（{@code CommandDescriptor.sessionRequired}），
     * 否则插件新注册一条命令时，外壳无从得知它需不需要会话。
     * <p>
     * 走到 {@code false} 分支的就是「要发给模型」的那一类：普通文本，或者<b>在首页手敲了一条需要会话的
     * 命令</b>（{@code /compact}）——按约定它当作用户的话发出去。
     * <p>
     * <b>输入指令排在命令域之后、对话之前</b>：{@code !} 这类行首标记与 {@code /} 不会撞车，
     * 而它需要会话（结果要落进历史），因此到了这一步就先建会话再交给内核解析。没有插件认领时
     * 返回空，输入原样变成一次普通对话——这正是「卸了插件就没有那个语法」的落点。
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
            if (ShellCommand.isThinkingCommand(text)) {
                toggleThinking();
            } else if (UiCommand.isUi(text)) {
                executeUi(text);
            } else if (MouseCommand.isMouse(text)) {
                executeMouse(text);
            } else {
                exitShell();
            }
            return;
        }
        String sessionId = currentSessionIdOrNull();
        if (commands.shouldRunAsCommand(text, sessionId != null)) {
            executeCommand(text, sessionId);
            return;
        }
        if (sessionId == null) {
            // 首页上要发给模型或交给输入指令：先建会话，界面随之进入会话页
            sessionId = createSession();
        }
        if (startDirective(text, sessionId)) {
            return;
        }
        startTurn(text, sessionId);
    }

    /**
     * 尝试把一行输入当作输入指令启动。
     * <p>
     * <b>解析放在重置暂存区之前，但执行之后</b>：解析是同步的纯函数（没有指令就什么都不做），
     * 先把暂存区重置再提交执行，才不会把执行线程可能已经写出的第一段实时输出抹掉。
     *
     * @param text      用户输入原文
     * @param sessionId 当前会话标识
     * @return 已启动指令返回 {@code true}；无人认领返回 {@code false}（调用方按普通对话处理）
     */
    private boolean startDirective(String text, String sessionId) {
        Optional<InputDirectiveCall> call = inputDirectives.resolve(sessionId, text);
        if (!call.isPresent()) {
            return false;
        }
        // 必须先重置：执行线程在提交后的任意时刻就可能开始产出实时输出
        chatState.beginDirective();
        uiCache.invalidate();
        try {
            InputDirectiveRun run = inputDirectives.start(sessionId, call.get(),
                    new TuiReActListener(chatState.getInflight()));
            chatState.bindDirective(run);
        } catch (JellyfishException e) {
            LOG.warn("TUI 输入指令启动失败：{}", e.getMessage());
            chatState.getInflight().finish(InflightTurn.Outcome.ERROR, e.getMessage());
        }
        return true;
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
     * 切换思考过程展开状态，并把新状态贴成一条外壳提示。
     * <p>
     * <b>为什么要回一条提示</b>：折叠态与展开态在屏幕上的差别是「一行的还是一片」，
     * 而当前屏幕可能压根<em>没有</em>思考块（比如刚启动）——那时候按 {@code Ctrl+T} 屏幕毫无变化，
     * 没有提示就与「按键没生效」无法区分。
     * <p>
     * 提示不进会话，因此不会污染发给模型的历史；它也<b>不建会话</b>，在首页上按也一样能用。
     */
    private void toggleThinking() {
        boolean expanded = chatState.toggleThinking();
        chatState.appendNotice("思考过程：" + (expanded ? "已展开" : "已折叠"), ShellNotice.Kind.INFO);
    }

    /**
     * 执行一条 {@code /mouse} 命令：把鼠标交还终端或收回应用。
     * <p>
     * 与 {@code /ui} 一样贴成外壳提示，但<b>不走 {@link CommandManager}</b>：鼠标捕获是终端能力，内核没有这个概念。
     *
     * @param text 命令原文
     */
    private void executeMouse(String text) {
        Optional<Boolean> target = MouseCommand.targetOf(text, mouseCaptured);
        if (!target.isPresent()) {
            chatState.appendNotice(text, MouseCommand.usageError(), ShellNotice.Kind.ERROR);
            return;
        }
        boolean applied = setMouseCapture(target.get());
        // 提示按「实际生效的状态」给而不是按目标状态：终端写失败时状态没变，
        // 屏幕上却写着「已交还」会让用户对着不能选中的界面找问题
        chatState.appendNotice(text, MouseCommand.notice(mouseCaptured),
                applied ? ShellNotice.Kind.INFO : ShellNotice.Kind.ERROR);
    }

    /**
     * 切换鼠标捕获（{@code Ctrl+O}）。
     * <p>
     * <b>为什么要回一条提示</b>：两种状态在屏幕上的差别只在「鼠标归谁管」，
     * 不按一下再拖选是看不出来的；而交还期间滚轮确实不工作，没有提示就与「界面卡住了」无法区分。
     * 提示不进会话，因此不会污染发给模型的历史；它也不需要会话，在首页上按同样可用。
     */
    private void toggleMouseCapture() {
        boolean applied = setMouseCapture(!mouseCaptured);
        chatState.appendNotice(MouseCommand.notice(mouseCaptured),
                applied ? ShellNotice.Kind.INFO : ShellNotice.Kind.ERROR);
    }

    /**
     * 把运行期的鼠标捕获状态写到终端。
     * <p>
     * <b>为什么是「已一致就直接返回」而不是无脑写一遍</b>：{@code /mouse on} 在已捕获态上是合法的空操作，
     * 此时再往终端写一次转义序列只会平白多一次输出（终端模式本来就是幂等的）。
     * <p>
     * 写失败只记日志并把状态留在原处：终端不认这些模式时界面仍应照常运行，
     * 提示由调用方按「实际生效的状态」给出。
     *
     * @param captured 目标状态（{@code true} 为收回应用捕获）
     * @return 状态已生效返回 {@code true}；写终端失败返回 {@code false}
     */
    private boolean setMouseCapture(boolean captured) {
        if (captured == mouseCaptured) {
            return true;
        }
        try {
            if (captured) {
                runner().tuiRunner().backend().enableMouseCapture(MOUSE_MOTION);
            } else {
                runner().tuiRunner().backend().disableMouseCapture();
            }
            mouseCaptured = captured;
            return true;
        } catch (IOException e) {
            LOG.warn("切换鼠标捕获失败：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 退出外壳：先把终端还原成启动配置要求的鼠标模式，再让框架收尾。
     * <p>
     * <b>为什么必须自己还原</b>：框架只在启动与关闭时按 {@code TuiConfig} 设置鼠标上报模式，不感知运行期的
     * {@code /mouse}。若用户以 {@code -Djellyfish.tui.mouseCapture=false} 启动、又在界面里把鼠标收回应用，
     * 关闭时框架认为「本来就没开」不会关掉上报，终端就会带着鼠标模式回到 shell——之后每次移动指针
     * 都会往命令行里吐转义序列。因此只要运行期状态与启动配置不一致，退出前必须自己关掉。
     * <p>
     * 其余组合由框架的关闭路径负责（已捕获且配置要求捕获时它会关，已交还时配置也一定是关的）。
     * 写失败只记日志：此刻界面正在收尾，没有可展示提示的地方。
     */
    private void exitShell() {
        if (mouseCaptureNeedsRestore(mouseCaptured, mouseCaptureEnabled())) {
            setMouseCapture(false);
        }
        quit();
    }

    /**
     * 判断退出前是否需要自己关闭鼠标上报。
     * <p>
     * 单独抽成纯函数是为了让这条容易漏掉的规则能被断言：漏一次就是终端在退出后继续上报鼠标。
     *
     * @param captured   运行期是否处于捕获态
     * @param configured 启动配置是否要求捕获
     * @return 需要自己关闭返回 {@code true}
     */
    static boolean mouseCaptureNeedsRestore(boolean captured, boolean configured) {
        return captured && !configured;
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
     * 装配首页（无会话）状态栏数据：展示「将要使用的」agent、模型与权限模式。
     * <p>
     * 首页没有会话，但状态栏也不应该是一片空白：用户正要看的就是「现在如果用，会用哪个 agent 与模型」，
     * 而 {@code /model} {@code /agent} {@code /mode} 在首页改的就是这份待生效默认值
     * （{@link SessionDefaults}）。因此这里按「待生效默认值 → 配置默认值」两级解析：
     * 不读第一级的话，用户改完会看到状态栏仍显示旧值，与实际将要用到的对不上。
     * <p>
     * 解析失败（没配模型）只退回 {@code null}，让状态栏退回「默认」字样，不因配置问题整行消失。
     *
     * @return 状态栏数据，保证非 {@code null}
     */
    private StatusBarView.Info homeStatusInfo() {
        String provider = null;
        String model = null;
        int contextLength = 0;
        try {
            ResolvedModel resolved = resolvePendingModel();
            provider = resolved == null ? null : resolved.getProviderName();
            model = resolved == null ? null : resolved.getModelName();
            contextLength = resolved == null ? 0 : resolved.getModel().getContextLength();
        } catch (JellyfishException e) {
            LOG.debug("首页解析默认模型失败：{}", e.getMessage());
        }
        SessionDefaults.Values defaults = sessionDefaults.snapshot();
        String agentId = defaults.getAgentId() == null ? defaultAgentId() : defaults.getAgentId();
        PermissionMode mode = defaults.getPermissionMode() == null
                ? PermissionMode.NORMAL : defaults.getPermissionMode();
        return new StatusBarView.Info(agentId, provider, model, mode,
                System.getProperty("user.dir"), 0L, contextLength, null);
    }

    /**
     * 解析首页上「将要使用」的模型：待生效默认值优先，未设置时才跟随配置默认。
     * <p>
     * 待生效默认值里可能只设了一半（例如只有 provider）——那不是一个可解析的模型，
     * 此时回退到配置默认值：状态栏显示一个真实会用的模型，比显示半个意义大。
     *
     * @return 解析结果；解析不到时返回 {@code null}
     * @throws JellyfishException 配置里没有可用模型时抛出
     */
    private ResolvedModel resolvePendingModel() {
        SessionDefaults.Values defaults = sessionDefaults.snapshot();
        if (defaults.getProvider() != null && defaults.getModel() != null) {
            return models.resolve(defaults.getProvider(), defaults.getModel());
        }
        return models.resolveDefault();
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
            // 交还 / 收回鼠标先行处理：它改的是终端能力而不是界面状态，因此不受「模态吞掉一切」约束
            // ——审批浮层里那一段长命令恰恰是最想复制的东西
            if (action == InputAction.TOGGLE_MOUSE) {
                toggleMouseCapture();
                return EventResult.HANDLED;
            }
            if (approvals.pending().isPresent()) {
                return handleApproval(action);
            }
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
                    // 先收起浮层再谈中断：无进行中工作时时 cancelTurn 是空操作，两者可以共存
                    completion.dismiss();
                    referenceCompletion.dismiss();
                    chatState.cancelTurn();
                    return EventResult.HANDLED;
                case TOGGLE_THINKING:
                    toggleThinking();
                    return EventResult.HANDLED;
                case QUIT:
                    exitShell();
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
                return handleReferenceCompletion(action);
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
         * 处理行内引用补全的导航与接受。
         * <p>
         * <b>面板没弹时一律返回 {@code UNHANDLED}</b>：{@code ↑}/{@code ↓} 要落回输入框做光标移动。
         * 与命令补全不同的是，这里会先重新问一次内核——光标位置是片段的一部分，
         * 而按键处理发生在上一帧之后，不能假定状态还是最新的。
         *
         * @param action 补全动作
         * @return 处理结果
         */
        private EventResult handleReferenceCompletion(InputAction action) {
            syncReferenceCompletion();
            if (!referenceCompletion.isActive() || referenceCompletion.getCandidates().isEmpty()) {
                return EventResult.UNHANDLED;
            }
            switch (action) {
                case COMPLETE_PREV:
                    referenceCompletion.moveUp();
                    return EventResult.HANDLED;
                case COMPLETE_NEXT:
                    referenceCompletion.moveDown();
                    return EventResult.HANDLED;
                case COMPLETE_ACCEPT:
                    return acceptReference();
                default:
                    return EventResult.UNHANDLED;
            }
        }

        /**
         * 确认引用补全的选中项：把片段换成候选的插入文本。
         * <p>
         * 目录候选接受后不关闭面板（用户显然是继续往里钻），文件候选则记入「已关闭」，
         * 面板自动收起——这条规则归状态对象，这里不重复判断。
         *
         * @return 处理结果
         */
        private EventResult acceptReference() {
            String accepted = referenceCompletion.accept(input.text());
            if (accepted == null) {
                return EventResult.UNHANDLED;
            }
            input.replaceText(accepted);
            // 输入变了，下一帧会重新问内核；先让缓存键失效，避免复用旧片段的候选
            lastReferenceQueryKey = null;
            return EventResult.HANDLED;
        }

        /**
         * 处理审批浮层的按键。
         * <p>
         * <b>审批浮层是最强模态</b>：除导航、确认、拒绝与退出外，其余按键一律吞掉。
         * 它是唯一一个「背后有线程在等」的界面，误操作（例如把参数敲进输入框再发送）
         * 只会把上一回合的输出变成一堆无效输入。
         * <p>
         * <b>{@code Esc} 是「拒绝 + 中断回合」而不是单纯拒绝</b>：用户的意图是「停」——
         * 只拒绝本次调用的话，模型收到拒绝理由后很可能换个方式接着试，看起来像没停下来。
         * 拒绝会立刻解开阻塞的 {@code react} 线程，接着取消标记会让回合在下一个检查点收敛。
         *
         * @param action 按键动作
         * @return 处理结果
         */
        private EventResult handleApproval(InputAction action) {
            ApprovalChannel.Pending pending = approvals.pending().orElse(null);
            if (pending == null) {
                // 本帧刚被超时 / 关闭裁决掉：不把这次按键算成任何操作
                return EventResult.HANDLED;
            }
            syncApproval(pending);
            switch (action) {
                case COMPLETE_PREV:
                    approvalPicker.moveUp();
                    return EventResult.HANDLED;
                case COMPLETE_NEXT:
                    approvalPicker.moveDown();
                    return EventResult.HANDLED;
                case COMPLETE_ACCEPT:
                    resolveApproval(pending, ApprovalPrompt.isApproved(approvalPicker.selected()));
                    return EventResult.HANDLED;
                case CANCEL:
                    resolveApproval(pending, false);
                    chatState.cancelTurn();
                    return EventResult.HANDLED;
                case QUIT:
                    exitShell();
                    return EventResult.HANDLED;
                default:
                    return EventResult.HANDLED;
            }
        }

        /**
         * 回填审批结论并收起浮层。
         *
         * @param pending  待审批请求
         * @param approved 是否批准
         */
        private void resolveApproval(ApprovalChannel.Pending pending, boolean approved) {
            approvals.resolve(pending.getId(), approved);
            approvalPicker.dismiss();
            renderedApprovalId = null;
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
                    exitShell();
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
     * <p>
     * <b>交还终端期间这里什么也收不到</b>：{@code /mouse}（{@code Ctrl+O}）关掉鼠标上报后终端不再发鼠标事件，
     * 滚轮由终端自己处置（多数终端把它翻译成 {@code ↑}/{@code ↓}）。这不是缺陷，而是换取
     * 「终端本地拖选可复制」的那份代价本身。
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
