package zcd.jellyfish.core.input;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ExtensionException;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.InputDirectiveRequest;
import zcd.jellyfish.api.extension.InputDirectiveResult;
import zcd.jellyfish.api.extension.InputReferenceChoice;
import zcd.jellyfish.api.extension.InputReferenceDescriptor;
import zcd.jellyfish.api.extension.InputReferenceRequest;
import zcd.jellyfish.api.extension.InputReferenceResult;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.core.ReActListener;
import zcd.jellyfish.core.tool.ToolExecutor;
import zcd.jellyfish.infra.extension.DescriptorBinding;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 输入指令服务：把「用户在输入框里用的特殊语法」变成内核动作，外壳中立。
 * <p>
 * <b>两条能力面，各自对应一个扩展点</b>：
 * <ul>
 *     <li>{@link #submit}：行首标记的<b>执行</b>（如 {@code !}）——解析一次、异步跑工具、把结果
 *     作为一条 user 消息落进会话。执行体是 {@link ToolExecutor}，因此权限、审批、截断、落盘
 *     与模型发起的工具调用完全同一条路径；</li>
 *     <li>{@link #complete}：行内标记的<b>补全</b>（如 {@code @}）——纯只读查询，渲染线程同步调用。</li>
 * </ul>
 * <b>为什么执行必须内核做</b>：{@link InputDirectiveResult} 只是声明，插件拿不到
 * {@code PermissionManager}，因此插件不可能自己起进程或写文件。把两个能力面都收在这里，
 * 「插件只声明、内核才执行」这条边界就有一个明确的落点。
 * <p>
 * <b>没有插件的标记不存在</b>：注册表里查不到处理器就当作「这行输入是普通文本」返回空，
 * 由外壳继续走对话路径。这正是「卸了 shell 插件就没有 {@code !}」的实现方式，
 * 不需要外壳维护一份「哪些标记需要哪个插件」的名单。
 * <p>
 * <b>结果为什么落成 user 消息而不是 tool 消息</b>：tool 消息必须与一条 {@code assistant.toolCalls}
 * 配对，而这里根本没有模型回合（用户自己敲的命令）。落成 user 消息在语义上没有欺骗，
 * 且对所有厂商都合法；它同时参与后续的上下文裁剪与压缩，与普通历史一视同仁。
 * <p>
 * <b>线程</b>：{@link #submit} 在调用线程上只做「解析 + 建句柄」，工具在自持的
 * {@code input-directive} 线程池上跑；{@link #complete} 必然在渲染线程上同步跑完。
 *
 * @author zcd
 */
@Singleton
public class InputDirectives implements AutoCloseable {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(InputDirectives.class);

    /** 输入指令执行器线程数上限。 */
    private static final int MAX_THREADS = 4;

    /** 输入指令执行器等待队列容量。 */
    private static final int QUEUE_CAPACITY = 32;

    /** 输入指令执行器空闲回收时间（秒）。 */
    private static final long KEEP_ALIVE_SECONDS = 60L;

    /** 落会话时给结果加的来源前缀，让模型分清「用户自己跑的」与「它自己跑的」。 */
    private static final String MESSAGE_PREFIX = "[手动执行] $ ";

    /** 同步扩展点策略：两个输入扩展点都从同一份注册表取。 */
    private final ExtensionRegistry extensions;

    /** 工具执行器：权限判定与截断的唯一入口。 */
    private final ToolExecutor toolExecutor;

    /** 会话域服务：取会话、追加结果消息。 */
    private final SessionManager sessionManager;

    /** 专用执行器。 */
    private final ExecutorService executor;

    /** 在途执行：关闭时逐个取消，让命令行尽快收敛。 */
    private final Set<InputDirectiveRun> activeRuns =
            Collections.newSetFromMap(new ConcurrentHashMap<InputDirectiveRun, Boolean>());

    /**
     * 构造输入指令服务并创建专用执行器。
     *
     * @param extensions     同步扩展点策略，不可为 {@code null}
     * @param toolExecutor   工具执行器，不可为 {@code null}
     * @param sessionManager 会话域服务，不可为 {@code null}
     */
    @Inject
    public InputDirectives(ExtensionRegistry extensions, ToolExecutor toolExecutor,
                           SessionManager sessionManager) {
        this(extensions, toolExecutor, sessionManager, createExecutor());
    }

    /**
     * 测试用构造器：注入执行器以便控制指令线程。
     *
     * @param extensions     同步扩展点策略，不可为 {@code null}
     * @param toolExecutor   工具执行器，不可为 {@code null}
     * @param sessionManager 会话域服务，不可为 {@code null}
     * @param executor       专用执行器，不可为 {@code null}
     */
    InputDirectives(ExtensionRegistry extensions, ToolExecutor toolExecutor, SessionManager sessionManager,
                    ExecutorService executor) {
        this.extensions = Objects.requireNonNull(extensions, "extensions must not be null");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor must not be null");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    /**
     * 解析并启动一条输入指令。
     * <p>
     * <b>解析是同步的，执行是异步的</b>：解析只调用插件的纯函数（必须快），据此判断「这行输入
     * 有没有人认领」；认领了就建句柄并提交执行，立即返回。解析结果是不是工具调用意图决定返回值：
     * {@code unclaimed} 与「没有处理器」都返回空，外壳据此把这行输入当普通文本。
     * <p>
     * <b>结束通知在提交之前就交进去</b>（{@code completion}）：指令可能短到在你拿到句柄之前
     * 就已经跑完，那时才注册回调会漏掉那一次通知——而可靠 lane 的契约是「每个标识恰好一条终态」。
     *
     * @param sessionId  会话标识，不可为空白
     * @param input      用户输入原文，可为 {@code null}
     * @param listener   工具执行的流式回调（实时输出），可为 {@code null}
     * @param completion 结束通知，不可为 {@code null}
     * @return 执行句柄；没有指令认领时为空
     * @throws zcd.jellyfish.api.JellyfishException 会话不存在时抛出（调用方应保证会话已建）、
     *                                              或指令执行队列已满时抛出
     */
    public Optional<InputDirectiveRun> submit(String sessionId, String input, ReActListener listener,
                                              InputDirectiveCompletion completion) {
        Optional<InputDirectiveCall> call = resolve(sessionId, input);
        if (!call.isPresent()) {
            return Optional.empty();
        }
        return Optional.of(start(sessionId, call.get(), listener, completion));
    }

    /**
     * 解析一行输入：有没有插件要认领它、要调哪个工具。
     * <p>
     * <b>同步且必须纯解析</b>：只调用插件的解析函数（不得起进程、不得写盘、不得阻塞），
     * 因此外壳可以在「重置界面暂存区」之前安全地调用它。返回空表示这行输入是普通文本。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @param input     用户输入原文，可为 {@code null}
     * @return 已解析的指令；没有指令认领时为空
     */
    public Optional<InputDirectiveCall> resolve(String sessionId, String input) {
        String marker = leadingMarker(input);
        if (marker == null) {
            return Optional.empty();
        }
        ExtensionHandler<InputDirectiveRequest, InputDirectiveResult> handler = directiveHandler(marker);
        if (handler == null) {
            return Optional.empty();
        }
        InputDirectiveResult result;
        try {
            result = extensions.invoke(handler, new InputDirectiveRequest(marker, input, sessionId));
        } catch (RuntimeException e) {
            // 解析失败不升级成界面故障：这行输入按普通文本继续（与「没有插件认领」同一处置）
            LOG.warn("输入指令解析失败: marker={} sessionId={}", marker, sessionId, e);
            return Optional.empty();
        }
        if (result == null || !result.isToolCall()) {
            return Optional.empty();
        }
        return Optional.of(new InputDirectiveCall(marker, input == null ? "" : input.trim(),
                result.getToolName(), result.getArguments()));
    }

    /**
     * 启动一条已解析的指令：跑工具，异步推进。
     * <p>
     * <b>调用方必须先做好自己的准备工作</b>（如重置界面暂存区）——执行线程在本方法返回前就可能
     * 已经开始产出实时输出。
     * <p>
     * <b>队列满时抛异常，而不是静默丢或就地改成同步跑</b>：指令队列是「等一个空闲执行线程」的场所，
     * 满员说明这一批指令已经超出内核的处理能力。此刻就地同步跑会占住调用方（渲染线程 / HTTP 线程），
     * 静默丢则让用户以为命令跑了。异常类型是 {@code JellyfishException}：它是外壳唯一声明会处理的
     * 一类失败，{@code RejectedExecutionException} 会一路穿过 {@code ConversationService.submit} 的契约。
     *
     * @param sessionId  会话标识，不可为空白
     * @param call       已解析的指令，不可为 {@code null}
     * @param listener   工具执行的流式回调（实时输出），可为 {@code null}
     * @param completion 结束通知，不可为 {@code null}
     * @return 执行句柄，保证非 {@code null}
     * @throws zcd.jellyfish.api.JellyfishException 会话不存在时抛出（调用方应保证会话已建）、
     *                                              或执行队列已满时抛出
     */
    public InputDirectiveRun start(String sessionId, InputDirectiveCall call, ReActListener listener,
                                   InputDirectiveCompletion completion) {
        Objects.requireNonNull(call, "call must not be null");
        Objects.requireNonNull(completion, "completion must not be null");
        Session session = sessionManager.require(sessionId);
        InputDirectiveRun run = new InputDirectiveRun(UUID.randomUUID().toString(), call.getMarker(),
                call.getInput(), completion);
        activeRuns.add(run);
        try {
            run.submit(executor, () -> execute(run, session, call, listener));
        } catch (RejectedExecutionException e) {
            // 任务没被受理：把在途条目摘掉——留着它既没有任何人会来执行，也会让关闭时的取消清单越来越长
            activeRuns.remove(run);
            LOG.warn("输入指令提交被拒（队列已满）: runId={} queueCapacity={}", run.getRunId(), QUEUE_CAPACITY);
            throw new JellyfishException("输入指令队列已满（正在跑 " + MAX_THREADS + " 条、排队 " + QUEUE_CAPACITY
                    + " 条）：请稍后重试，或等前一条命令跑完", e);
        }
        return run;
    }

    /**
     * 查询行内引用候选。
     * <p>
     * 片段切分在这里统一完成：从光标向前扫到空白或行首，得到「以标记开头的片段」，
     * 标记之后的原文就是交给插件的 {@code token}。找不到已注册的标记就返回
     * {@link InputReferenceCompletion#empty()}，外壳据此不弹面板。
     * <p>
     * <b>本方法在渲染线程上同步执行</b>，因此把「插件处理器必须快」这条约束原样传导给插件。
     *
     * @param input     输入框全文，可为 {@code null}
     * @param cursor    光标字符偏移
     * @param sessionId 会话标识，可为 {@code null}
     * @return 补全结果，保证非 {@code null}
     */
    public InputReferenceCompletion complete(String input, int cursor, String sessionId) {
        List<String> markers = referenceMarkers();
        if (markers.isEmpty()) {
            return InputReferenceCompletion.empty();
        }
        String text = input == null ? "" : input;
        int position = Math.max(0, Math.min(cursor, text.length()));
        int start = scanBack(text, position);
        int end = scanForward(text, position);
        if (start >= end) {
            return InputReferenceCompletion.empty();
        }
        String segment = text.substring(start, end);
        String marker = segment.substring(0, 1);
        if (!markers.contains(marker)) {
            return InputReferenceCompletion.empty();
        }
        ExtensionHandler<InputReferenceRequest, InputReferenceResult> handler = referenceHandler(marker);
        if (handler == null) {
            return InputReferenceCompletion.empty();
        }
        String token = segment.substring(1);
        try {
            InputReferenceResult result = extensions.invoke(handler,
                    new InputReferenceRequest(marker, token, text, position, sessionId));
            List<InputReferenceChoice> choices =
                    result == null ? Collections.<InputReferenceChoice>emptyList() : result.getChoices();
            return InputReferenceCompletion.of(start, end, marker, choices);
        } catch (RuntimeException e) {
            LOG.warn("引用补全失败: marker={} sessionId={}", marker, sessionId, e);
            return InputReferenceCompletion.empty();
        }
    }

    @Override
    public void close() {
        // 先取消在途执行：命令行的终止链靠令牌触发，等执行器自己退出会把关闭拖到工具超时
        for (InputDirectiveRun run : activeRuns) {
            run.cancel();
        }
        activeRuns.clear();
        executor.shutdownNow();
    }

    /**
     * 取当前在途（或排队中）的指令条数。
     * <p>
     * <b>包私有接缝</b>：只供同包测试断言「提交被拒之后没有把条目留在表里」。那条残留没有别的
     * 观察点——它是内存里的一个条目；而它的后果会一直累积（关闭时的取消清单越来越长，且那些条目
     * 永远不会被执行）。
     *
     * @return 在途条数
     */
    int activeRunCount() {
        return activeRuns.size();
    }

    /**
     * 执行一次指令：跑工具，然后把「回显 + 结果」作为一条 user 消息落进会话。
     * <p>
     * <b>落盘方式</b>：这里不在回合作用域内，因此 {@code appendMessage} 会即时落盘——正是想要的
     * 「命令一跑完就留下痕迹」，不需要额外的 flush。
     *
     * @param run      执行句柄
     * @param session  会话运行态
     * @param result   解析结果（工具调用意图）
     * @param listener 流式回调，可为 {@code null}
     */
    private void execute(InputDirectiveRun run, Session session, InputDirectiveCall call,
                         ReActListener listener) {
        String toolName = call.getToolName();
        String echo = echoOf(run);
        try {
            ToolCallResult outcome = toolExecutor.execute(session, run.token(), run.getRunId(), toolName,
                    call.getArguments(), listener);
            appendResult(session, echo, outputOf(outcome));
        } catch (RuntimeException e) {
            LOG.warn("输入指令执行失败: runId={} tool={}", run.getRunId(), toolName, e);
            appendResult(session, echo, "执行失败：" + messageOf(e));
        } finally {
            activeRuns.remove(run);
            // 通知排在最后：订阅者收到「结束了」时，结果消息已经落库，读会话就能看到它。
            // 通知本身抛错不上抛——它是通知，不是执行结果（与「落盘失败只记 WARN」同一口径）
            notifyFinished(run);
        }
    }

    /**
     * 通知提交方「这条指令结束了」。
     * <p>
     * 这是可靠 lane 上那条终态事件的唯一出处：错过它，按「每个标识恰好一条终态」实现的订阅者
     * 会一直等下去。通知失败只记 WARN，因为指令本身已经跑完、结果已经落库，把整次执行升级成异常
     * 既补不回那条事件，也会让调用方以为指令没跑成。
     *
     * @param run 已结束的指令句柄
     */
    private void notifyFinished(InputDirectiveRun run) {
        try {
            run.notifyFinished();
        } catch (RuntimeException e) {
            LOG.warn("输入指令结束通知失败: runId={}", run.getRunId(), e);
        }
    }

    /**
     * 取回显用的命令文本：去掉首部标记并修剪。
     * <p>
     * 落会话的文本面向模型，{@code [手动执行] $ !ls} 里的那个 {@code !} 是输入框的语法，
     * 对模型没有意义（它看到的应当是一条被用户手动跑过的 shell 命令）。
     *
     * @param run 执行句柄
     * @return 回显命令，保证非 {@code null}
     */
    private static String echoOf(InputDirectiveRun run) {
        String command = run.getCommand();
        String marker = run.getMarker();
        return command.startsWith(marker) ? command.substring(marker.length()).trim() : command;
    }

    /**
     * 把结果作为一条 user 消息落进会话，落盘失败只记 WARN。
     * <p>
     * <b>为什么失败不上抛</b>：执行已经完成，用户要的是结果留在历史里；落盘失败是存储问题，
     * 把整次指令升级成异常既补不回来也无从补救（与回合级 flush 的失败语义同口径）。
     *
     * @param session 会话运行态
     * @param command 回显的输入原文
     * @param output  结果文本，可为 {@code null}
     */
    private void appendResult(Session session, String command, String output) {
        try {
            sessionManager.appendMessage(session.getSessionId(),
                    LlmMessage.user(MESSAGE_PREFIX + command + "\n" + (output == null ? "" : output)), null);
        } catch (RuntimeException e) {
            LOG.warn("输入指令结果落盘失败: sessionId={}", session.getSessionId(), e);
        }
    }

    /**
     * 取输入去掉首尾空白后的首字符标记。
     *
     * @param input 用户输入原文，可为 {@code null}
     * @return 标记字符；输入为空白时返回 {@code null}
     */
    private static String leadingMarker(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        return trimmed.isEmpty() ? null : trimmed.substring(0, 1);
    }

    /**
     * 取输入指令处理器。
     *
     * @param marker 标记字符
     * @return 处理器；未注册或注册冲突时返回 {@code null}
     */
    private ExtensionHandler<InputDirectiveRequest, InputDirectiveResult> directiveHandler(String marker) {
        try {
            return extensions.handler(InputDirectiveRequest.class, marker);
        } catch (ExtensionException e) {
            if (e.getCode() != ExtensionException.Code.NO_HANDLER) {
                // 多命中说明注册表被用错了（本扩展点要求同键唯一），只记日志并按「无人认领」处理
                LOG.warn("输入指令注册冲突: marker={}", marker);
            }
            return null;
        }
    }

    /**
     * 取引用补全处理器。
     *
     * @param marker 标记字符
     * @return 处理器；未注册或注册冲突时返回 {@code null}
     */
    private ExtensionHandler<InputReferenceRequest, InputReferenceResult> referenceHandler(String marker) {
        try {
            return extensions.handler(InputReferenceRequest.class, marker);
        } catch (ExtensionException e) {
            if (e.getCode() != ExtensionException.Code.NO_HANDLER) {
                LOG.warn("引用补全注册冲突: marker={}", marker);
            }
            return null;
        }
    }

    /**
     * 取当前已注册的全部引用标记。
     *
     * @return 不可变标记列表，无注册时为空列表
     */
    private List<String> referenceMarkers() {
        List<DescriptorBinding<InputReferenceDescriptor>> bindings = extensions.descriptorBindings(
                InputReferenceRequest.class, InputReferenceDescriptor.class);
        if (bindings.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> markers = new ArrayList<String>(bindings.size());
        for (DescriptorBinding<InputReferenceDescriptor> binding : bindings) {
            if (binding.getRouteKey() != null) {
                markers.add(binding.getRouteKey());
            }
        }
        return markers;
    }

    /**
     * 从光标位置向前扫到空白或行首。
     *
     * @param text     输入全文
     * @param position 光标位置
     * @return 片段起点
     */
    private static int scanBack(String text, int position) {
        int start = position;
        while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) {
            start--;
        }
        return start;
    }

    /**
     * 从光标位置向后扫到空白或行尾。
     *
     * @param text     输入全文
     * @param position 光标位置
     * @return 片段终点
     */
    private static int scanForward(String text, int position) {
        int end = position;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        return end;
    }

    /**
     * 取工具结果的文本。
     *
     * @param outcome 工具结果，可为 {@code null}
     * @return 结果文本，可为 {@code null}
     */
    private static String outputOf(ToolCallResult outcome) {
        if (outcome == null || outcome.getOutput() == null) {
            return null;
        }
        return outcome.getOutput().toString();
    }

    /**
     * 取异常的可用消息。
     *
     * @param error 异常，可为 {@code null}
     * @return 消息文本，保证非 {@code null}
     */
    private static String messageOf(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    /**
     * 创建专用守护线程池。
     *
     * @return 执行器
     */
    private static ExecutorService createExecutor() {
        ThreadPoolExecutor threadPool = new ThreadPoolExecutor(MAX_THREADS, MAX_THREADS,
                KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "input-directive");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        threadPool.allowCoreThreadTimeOut(true);
        return threadPool;
    }
}
