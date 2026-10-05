package zcd.jellyfish.core.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.api.event.notification.ToolCallCompletedEvent;
import zcd.jellyfish.api.event.notification.ToolCallStartedEvent;
import zcd.jellyfish.api.extension.CancellationToken;
import zcd.jellyfish.api.extension.ExtensionException;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.PermissionDecision;
import zcd.jellyfish.api.extension.ToolArgumentDecision;
import zcd.jellyfish.api.extension.ToolArgumentPreRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolMetadata;
import zcd.jellyfish.api.extension.ToolOutputSink;
import zcd.jellyfish.api.extension.ToolResultAdjustment;
import zcd.jellyfish.api.extension.ToolResultPostRequest;
import zcd.jellyfish.core.ReActListener;
import zcd.jellyfish.core.runtime.RunContext;
import zcd.jellyfish.core.runtime.RunContextHolder;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.permission.PermissionManager;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;
import zcd.jellyfish.infra.tooloutput.ToolOutputLimiter;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 工具执行器：一次工具调用从「权限判定」到「回灌文本」的完整链路，只此一处。
 * <p>
 * <b>为什么把它从 {@code ReActLooper} 抽出来</b>：工具调用现在有两条发起方——模型（ReAct 循环）与用户
 * （输入指令，如 {@code !ls}）。执行语义里包含权限判定、审批、事件埋点、输出捕获与截断落盘、
 * 异常转结果文本，复制一份到第二条路径上就等于把这些口径维护两遍，漂移只是时间问题。
 * <p>
 * <b>调用方线程语义</b>：本类不做异步，在调用线程上同步执行完整个工具调用。因此 ReAct 循环在自己的
 * {@code react} 线程上调用，输入指令在自己的执行器线程上调用；两者都不允许在界面线程上直接调。
 * <p>
 * <b>权限是唯一入口</b>：任何发起方都必须经过 {@link #execute}——这也是「插件只能声明工具调用意图、
 * 不能自己执行」这条边界的落点：插件够不到 {@code PermissionManager}，而本类必然调用它。
 * <p>
 * <b>异常不逃逸</b>：工具抛错、权限拒绝、未知工具一律转成 {@link ToolCallResult} 的文本，
 * 让调用方（模型或上下文）能看见失败原因并自适应；只有编程错误（例如参数解析用了非法 JSON）才抛，
 * 但那同样会被本类接住并转成结果文本。
 * <p>
 * <b>七步顺序是一条不可重排的链</b>（规则见 {@code docs/constraints.md} 的「工具执行」）：
 * 参数解析 → <b>参数改写</b> → 权限判定 → 路由与调用 → <b>结果整形</b> → 截断与落盘 → 落会话与通知。
 * 两个「改写点」的位置各有硬理由：参数改写<b>必须在权限判定之前</b>（否则审批看到的参数与执行的参数
 * 不是同一份），结果整形<b>必须在截断之前</b>（否则信封与落盘文件永久分叉）。两者在无插件时
 * 都退化成空操作，行为与引入之前逐字段一致。
 *
 * @author zcd
 */
@Singleton
public class ToolExecutor {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ToolExecutor.class);

    /** 权限管理器：工具执行前的同步判定。 */
    private final PermissionManager permissionManager;

    /** 同步扩展点策略：工具路由。 */
    private final ExtensionRegistry extensions;

    /** 通知发布入口：工具埋点。 */
    private final EventPublisher events;

    /** 工具输出中间件：回灌前的唯一硬截断点。 */
    private final ToolOutputLimiter outputLimiter;

    /** 委派上下文持有者：本次调用属于哪个 run 只有它知道。 */
    private final RunContextHolder runContexts;

    /**
     * 构造工具执行器。
     *
     * @param permissionManager 权限管理器，不可为 {@code null}
     * @param extensions        同步扩展点策略，不可为 {@code null}
     * @param events            通知发布入口，不可为 {@code null}
     * @param outputLimiter     工具输出中间件，不可为 {@code null}
     * @param runContexts       委派上下文持有者，用来读出本次调用的 run 身份，不可为 {@code null}
     */
    @Inject
    public ToolExecutor(PermissionManager permissionManager, ExtensionRegistry extensions,
                        EventPublisher events, ToolOutputLimiter outputLimiter,
                        RunContextHolder runContexts) {
        this.permissionManager = Objects.requireNonNull(permissionManager, "permissionManager must not be null");
        this.extensions = Objects.requireNonNull(extensions, "extensions must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.outputLimiter = Objects.requireNonNull(outputLimiter, "outputLimiter must not be null");
        this.runContexts = Objects.requireNonNull(runContexts, "runContexts must not be null");
    }

    /**
     * 执行一次工具调用，参数是模型给出的 JSON 文本。
     * <p>
     * 供 ReAct 循环使用：模型返回的工具调用参数是 JSON 串，这里解析后再走同一条执行路径。
     * 解析失败也转成结果文本（模型能看懂并改正），与工具自身抛错的处置一致。
     * <p>
     * 本重载声明的发起方是 {@link ToolArgumentPreRequest.Source#MODEL}。
     *
     * @param session       会话运行态，不可为 {@code null}
     * @param cancellation  取消令牌，可为 {@code null}（按 {@link CancellationToken#NONE} 处理）
     * @param toolCallId    工具调用标识，不可为空白
     * @param toolName      工具名，不可为空白
     * @param argumentsJson 参数 JSON，可为空白（等价空参数）
     * @param listener      流式回调，可为 {@code null}（等价 {@link ReActListener#NOOP}）
     * @return 工具结果，保证非 {@code null}
     */
    public ToolCallResult execute(Session session, CancellationToken cancellation,
                                  String toolCallId, String toolName, String argumentsJson,
                                  ReActListener listener) {
        Objects.requireNonNull(session, "session must not be null");
        Map<String, Object> arguments;
        try {
            arguments = parseArguments(argumentsJson);
        } catch (RuntimeException e) {
            LOG.warn("工具参数解析失败: sessionId={} tool={}", session.getSessionId(), toolName, e);
            return new ToolCallResult(toolName, failureText(null, e));
        }
        return execute(session, cancellation, toolCallId, toolName, arguments, listener,
                ToolArgumentPreRequest.Source.MODEL);
    }

    /**
     * 执行一次工具调用，参数已是映射。
     * <p>
     * 供输入指令使用：插件声明的是结构化参数，不需要再过一遍 JSON。
     * <p>
     * 本重载声明的发起方是 {@link ToolArgumentPreRequest.Source#DIRECTIVE}。
     *
     * @param session      会话运行态，不可为 {@code null}
     * @param cancellation 取消令牌，可为 {@code null}（按 {@link CancellationToken#NONE} 处理）
     * @param toolCallId   工具调用标识，不可为空白
     * @param toolName     工具名，不可为空白
     * @param arguments    工具参数，可为 {@code null}（等价空参数）
     * @param listener     流式回调，可为 {@code null}（等价 {@link ReActListener#NOOP}）
     * @return 工具结果，保证非 {@code null}
     */
    public ToolCallResult execute(Session session, CancellationToken cancellation,
                                  String toolCallId, String toolName, Map<String, Object> arguments,
                                  ReActListener listener) {
        return execute(session, cancellation, toolCallId, toolName, arguments, listener,
                ToolArgumentPreRequest.Source.DIRECTIVE);
    }

    /**
     * 执行一次工具调用：参数已是映射，并显式声明发起方。
     * <p>
     * 两个公开重载只是填好 {@code source} 后走这一条路径，执行语义只有一份。
     *
     * @param session      会话运行态，不可为 {@code null}
     * @param cancellation 取消令牌，可为 {@code null}（按 {@link CancellationToken#NONE} 处理）
     * @param toolCallId   工具调用标识，不可为空白
     * @param toolName     工具名，不可为空白
     * @param arguments    工具参数，可为 {@code null}（等价空参数）
     * @param listener     流式回调，可为 {@code null}（等价 {@link ReActListener#NOOP}）
     * @param source       发起方，不可为 {@code null}
     * @return 工具结果，保证非 {@code null}
     */
    private ToolCallResult execute(Session session, CancellationToken cancellation, String toolCallId,
                                   String toolName, Map<String, Object> arguments, ReActListener listener,
                                   ToolArgumentPreRequest.Source source) {
        Objects.requireNonNull(session, "session must not be null");
        ReActListener effective = listener == null ? ReActListener.NOOP : listener;
        String sessionId = session.getSessionId();
        String agentId = session.getAgentId();
        // 第 2 步：参数改写。本类里最不能挪的一处位置，两个理由：
        //   · 必须在权限判定之前，否则审批浮层显示参数 A、真正执行参数 B（TOCTOU）；
        //   · 必须在 onToolCallStarted 之前，否则轨迹行与 --show-tool-args 打出来的是旧参数，
        //     与审批记录、与会话里落库的 toolCalls 不是同一份（四个显示面共用一份文本是既有纪律）
        ToolArgumentDecision decision = transformArguments(agentId, toolName, arguments, source, sessionId);
        Map<String, Object> effectiveArguments = decision.isReplace() ? decision.getArguments() : arguments;
        events.publish(new ToolCallStartedEvent(toolCallId, toolName, sessionId));
        effective.onToolCallStarted(toolCallId, toolName, effectiveArguments);
        if (decision.isDenied()) {
            // 与「权限拒绝」同形：不建捕获通道、不调用工具，但照旧发齐「开始 + 结束」两个埋点，
            // 让外壳与指标看到的是一次配对的失败调用，而不是一条只有结果的孤儿记录
            return rejected(session, toolCallId, toolName, decision.getReason(), effective);
        }
        long start = System.currentTimeMillis();
        // 捕获通道与取消令牌都随请求交给工具：无界输出的工具（命令行）靠前者不必物化整份输出，
        // 靠后者才能在用户按下 Esc 时被打断——同步派发不会中断正在执行的工具。
        // tee 把捕获到的片段同时转给外壳：它只是旁路（可丢、抛错被隔离），既不参与回灌也不落盘
        ToolOutputSink sink = outputLimiter.sink(sessionId, toolCallId, toolName,
                chunk -> effective.onToolCallOutput(toolCallId, toolName, chunk));
        ToolCallResult invoked;
        boolean success = true;
        try {
            invoked = invokeTool(session, cancellation, toolCallId, toolName, effectiveArguments, sink);
        } catch (RuntimeException e) {
            // 同步侧没有护栏，异常处置是调用点（这里）的责任：记失败、回灌、继续循环。
            // 失败原因进元数据：success 不落会话，只有元数据才能在重投影 / -resume 之后仍显示标记
            LOG.warn("工具执行失败: sessionId={} tool={}", sessionId, toolName, e);
            invoked = new ToolCallResult(toolName, failureText(sink, e), failureMetadata(e));
            success = false;
        } finally {
            // 工具自己应当已经收尾；这里是兜底，保证落盘句柄一定释放、临时文件一定改名。
            // 幂等，因此正常路径上再调一次不会有副作用
            sink.finish();
        }
        if (invoked == null) {
            // 处理器返回 null 是允许的（api 里 output 可为 null），这里补一个空结果，
            // 让下面那条「文本 + 元数据」的统一处理不必到处判空
            invoked = new ToolCallResult(toolName, null);
        }
        // 第 5 步：结果整形，必须在截断之前。截断之后回来改文本会产出「信封说截断了、正文却完整」的
        // 自相矛盾结果；而落盘文件是截断那一步写的，后置变换够不到它，改晚了就是永久分叉。
        // output 保持原始类型（String 或 Map/List）：截断要知道类型才能选对算法
        Object raw = invoked.getOutput();
        ToolResultAdjustment adjustment = adjustResult(agentId, toolName, effectiveArguments, raw,
                invoked.getMetadata(), ToolMetadata.failed(invoked.getMetadata()), sessionId);
        Object adjusted = adjustment.hasOutput() ? adjustment.getOutput() : raw;
        Map<String, Object> metadata = adjustment.hasMetadata() ? adjustment.getMetadata() : invoked.getMetadata();
        // 第 6 步：截断与落盘只在这里做一次：回灌给模型、写入会话、通知外壳看到的必须是同一份文本，
        // 否则会出现「界面显示全文、模型收到信封」这种无法排查的不一致
        String output = outputLimiter.limit(sessionId, toolCallId, toolName, adjusted);
        long duration = System.currentTimeMillis() - start;
        events.publish(new ToolCallCompletedEvent(toolCallId, toolName, success, duration,
                success ? null : output, sessionId));
        // 元数据不受截断影响：它描述的是「命令成没成」，与回灌文本被截成什么样无关
        effective.onToolCallCompleted(toolCallId, toolName, success, output, metadata);
        return new ToolCallResult(toolName, output, metadata);
    }

    /**
     * 跑一遍参数改写链，返回最终裁定。
     * <p>
     * <b>链式语义</b>（写在调用点的 {@code for} 循环里，注册表不参与）：每个处理器收到<b>上一个
     * 处理器产出的</b>参数（首个收到原始参数）；{@code ABSTAIN} 保持当前值继续；{@code REPLACE}
     * 替换当前值继续；{@code DENY} 立即短路。
     * <p>
     * <b>失败语义是 {@code ABSTAIN}</b>：异常隔离是调用点的责任（同步派发没有护栏），
     * 而插件坏掉时既不该放行也不该崩溃——与 {@code PermissionManager} 对插件拦截的处置同口径。
     * <p>
     * <b>无插件时返回 {@code ABSTAIN} 且不构造任何请求对象</b>：这是「装了插件与没装插件行为一致」
     * 的落点，调用方据此原样沿用传进来的参数。
     *
     * @param agentId   发起调用的 agentId，可为 {@code null}
     * @param toolName  工具名
     * @param arguments 当前参数，可为 {@code null}
     * @param source    发起方
     * @param sessionId 会话标识
     * @return 最终裁定；链上没有可用的处理器时为 {@link ToolArgumentDecision#abstain()}
     */
    private ToolArgumentDecision transformArguments(String agentId, String toolName, Map<String, Object> arguments,
                                                    ToolArgumentPreRequest.Source source,
                                                    String sessionId) {
        List<ExtensionHandler<ToolArgumentPreRequest, ToolArgumentDecision>> handlers =
                extensions.handlers(ToolArgumentPreRequest.class, null);
        if (handlers.isEmpty()) {
            return ToolArgumentDecision.abstain();
        }
        Map<String, Object> current = arguments;
        boolean replaced = false;
        for (ExtensionHandler<ToolArgumentPreRequest, ToolArgumentDecision> handler : handlers) {
            ToolArgumentDecision decision;
            try {
                decision = extensions.invoke(handler, new ToolArgumentPreRequest(agentId, toolName, current,
                        source, sessionId));
            } catch (RuntimeException e) {
                LOG.warn("参数改写处理器抛错，按不改处理: sessionId={} tool={}", sessionId, toolName, e);
                continue;
            }
            if (decision == null) {
                continue;
            }
            if (decision.isDenied()) {
                return decision;
            }
            if (decision.isReplace()) {
                current = decision.getArguments();
                replaced = true;
            }
        }
        return replaced ? ToolArgumentDecision.replace(current) : ToolArgumentDecision.abstain();
    }

    /**
     * 跑一遍结果整形链，返回累计后的裁定。
     * <p>
     * <b>与参数改写链的一处差别</b>：后者的「当前值」是整份参数（替换即整体换掉），
     * 而这里要分别累计 {@code output} 与 {@code metadata} 两个字段——一个处理器只改元数据时，
     * 它的前一个处理器改过的输出必须仍然生效。
     * <p>
     * <b>失败语义是 {@code ABSTAIN}</b>，理由同 {@link #transformArguments}。
     *
     * @param agentId   发起调用的 agentId，可为 {@code null}
     * @param toolName  工具名
     * @param arguments 实际使用的参数，可为 {@code null}
     * @param output    工具产出的原始结果，可为 {@code null}
     * @param metadata  结构化元数据，可为 {@code null}
     * @param failed    本次调用是否值得警示
     * @param sessionId 会话标识
     * @return 累计裁定；链上没有改动时为 {@link ToolResultAdjustment#abstain()}
     */
    private ToolResultAdjustment adjustResult(String agentId, String toolName, Map<String, Object> arguments,
                                              Object output, Map<String, Object> metadata, boolean failed,
                                              String sessionId) {
        List<ExtensionHandler<ToolResultPostRequest, ToolResultAdjustment>> handlers =
                extensions.handlers(ToolResultPostRequest.class, null);
        if (handlers.isEmpty()) {
            return ToolResultAdjustment.abstain();
        }
        Object adjustedOutput = null;
        Map<String, Object> adjustedMetadata = null;
        for (ExtensionHandler<ToolResultPostRequest, ToolResultAdjustment> handler : handlers) {
            ToolResultAdjustment adjustment;
            try {
                adjustment = extensions.invoke(handler, new ToolResultPostRequest(agentId, toolName, arguments,
                        adjustedOutput == null ? output : adjustedOutput,
                        adjustedMetadata == null ? metadata : adjustedMetadata, failed, sessionId));
            } catch (RuntimeException e) {
                LOG.warn("结果整形处理器抛错，按不改处理: sessionId={} tool={}", sessionId, toolName, e);
                continue;
            }
            if (adjustment == null || adjustment.isAbstain()) {
                continue;
            }
            if (adjustment.hasOutput()) {
                adjustedOutput = adjustment.getOutput();
            }
            if (adjustment.hasMetadata()) {
                adjustedMetadata = adjustment.getMetadata();
            }
        }
        if (adjustedOutput == null && adjustedMetadata == null) {
            return ToolResultAdjustment.abstain();
        }
        return ToolResultAdjustment.of(adjustedOutput, adjustedMetadata);
    }

    /**
     * 组装「参数被插件拒绝」的结果，并发齐本次调用的两个埋点。
     * <p>
     * <b>元数据里写 {@code REJECTED} 而不是 {@code FAILED}</b>：两者对界面都是警示（{@code failed}
     * 返回真），但对排查是两件事——{@code FAILED} 是「工具自己没成」，{@code REJECTED} 是
     * 「工具压根没跑」。区分开来之后，「工具失败率」这类指标不必去解析文案。
     * <p>
     * <b>不进权限审计</b>：权限事件回答的是「权限系统放没放行」，而这里是「插件拒了这条参数」，
     * 合进同一个事件会让权限计数失真。
     *
     * @param session  会话运行态，不可为 {@code null}
     * @param toolCallId 工具调用标识
     * @param toolName   工具名
     * @param reason     拒绝理由，可为 {@code null}
     * @param listener   流式回调，保证非 {@code null}
     * @return 失败结果，保证非 {@code null}
     */
    private ToolCallResult rejected(Session session, String toolCallId, String toolName, String reason,
                                    ReActListener listener) {
        String sessionId = session.getSessionId();
        String text = "插件拒绝参数：" + messageOf(reason);
        String output = outputLimiter.limit(sessionId, toolCallId, toolName, text);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put(ToolMetadata.KEY_TERMINAL, ToolMetadata.TERMINAL_REJECTED);
        Map<String, Object> immutable = Collections.unmodifiableMap(metadata);
        events.publish(new ToolCallCompletedEvent(toolCallId, toolName, false, 0L, output, sessionId));
        listener.onToolCallCompleted(toolCallId, toolName, false, output, immutable);
        return new ToolCallResult(toolName, output, immutable);
    }

    /**
     * 权限判定 + 路由 + 调用，返回工具输出对象。
     * <p>
     * <b>不在这里序列化</b>：截断必须知道「这是字符串还是结构化对象」才能选对算法，
     * 一旦在这里转成文本，那份信息就丢失了。
     *
     * @param session      会话运行态
     * @param cancellation 取消令牌，可为 {@code null}
     * @param toolCallId   工具调用标识
     * @param toolName     工具名
     * @param arguments    工具参数，可为 {@code null}
     * @param sink         输出捕获通道
     * @return 工具结果，可为 {@code null}（工具返回 {@code null} 时）
     */
    private ToolCallResult invokeTool(Session session, CancellationToken cancellation, String toolCallId,
                                      String toolName, Map<String, Object> arguments, ToolOutputSink sink) {
        PermissionDecision decision = permissionManager.decide(new PermissionCheckRequest(session.getAgentId(),
                toolName, arguments, session.getSessionId()));
        if (decision.isDenied()) {
            return new ToolCallResult(toolName, "权限拒绝：" + messageOf(decision.getReason()));
        }
        ExtensionHandler<ToolCallRequest, ToolCallResult> handler;
        try {
            handler = extensions.handler(ToolCallRequest.class, toolName);
        } catch (ExtensionException e) {
            if (e.getCode() == ExtensionException.Code.NO_HANDLER) {
                return new ToolCallResult(toolName, "未知工具：" + toolName);
            }
            return new ToolCallResult(toolName, "工具注册冲突：" + toolName);
        }
        // 调用者身份随请求交给工具：会话的派生来源来自会话本身，run 身份来自当前执行路径的上下文。
        // 读两次 current() 而不是先存局部变量，是为了让「不在 run 上」这件事在两次读取里保持同一个答案——
        // 它本来就是线程作用域的，同一条线程上不会中途变。
        return extensions.invoke(handler, new ToolCallRequest(toolName, arguments, session.getSessionId(),
                cancellation, sink, session.getParentSessionId(),
                runIdOf(runContexts.current()), rootRunIdOf(runContexts.current())));
    }

    /**
     * 取当前执行路径所属的 run 标识。
     * <p>
     * 不在任何 run 上（顶层回合、进程级调用、外壳线程）时返回 {@code null}——
     * 「没有身份」与「身份是空串」是两件事，前者正是调用方需要的信号。
     *
     * @param context 当前线程的上下文，可为 {@code null}
     * @return run 标识，可能为 {@code null}
     */
    private static String runIdOf(RunContext context) {
        return context == null ? null : context.getRunId();
    }

    /**
     * 取当前执行路径所属 run 树的根标识。
     *
     * @param context 当前线程的上下文，可为 {@code null}
     * @return 根 run 标识，可能为 {@code null}
     */
    private static String rootRunIdOf(RunContext context) {
        return context == null ? null : context.getRootRunId();
    }

    /**
     * 组装工具失败时的回灌文本。
     * <p>
     * <b>已捕获的输出不能丢</b>：命令跑到一半失败（或超时、被取消）时，已经产出的那部分输出
     * 往往是排查失败的唯一线索。因此先收尾捕获通道，把它的结果拼在错误信息前面，
     * 而不是让「工具执行失败」这一句话盖掉全部现场。
     *
     * @param sink  输出捕获通道，可为 {@code null}
     * @param error 失败原因
     * @return 回灌文本，保证非 {@code null}
     */
    private static String failureText(ToolOutputSink sink, RuntimeException error) {
        String message = "工具执行失败：" + messageOf(error);
        if (sink == null) {
            return message;
        }
        String captured;
        try {
            captured = sink.finish();
        } catch (RuntimeException e) {
            LOG.warn("工具输出收尾失败，仅回灌错误信息", e);
            return message;
        }
        if (captured == null || captured.isEmpty()) {
            return message;
        }
        return captured + "\n[" + message + "]";
    }

    /**
     * 组装工具抛异常时的结构化元数据。
     * <p>
     * 「工具抛异常」与「工具成功地报告了一个不成功的命令」是两条不同的路，但对界面而言是
     * <b>同一件事</b>：这一行值得警示。{@code success} 不落会话，因此只有把终止原因写进元数据，
     * 重启后的历史里标记才不会消失。
     * <p>
     * 失败原因只取工具按约定抛出的 {@link JellyfishException} 的首行：那条消息是<b>工具自己</b>
     * 写给「为什么没成」的一句话（例如「文件不存在: /x/y」），内核只是转述，不算替工具编措辞；
     * 其余 {@code RuntimeException}（NPE / 类型错）是实现细节，不该出现在界面上。
     *
     * @param error 工具抛出的异常，不可为 {@code null}
     * @return 不可变元数据，保证非 {@code null}
     */
    private static Map<String, Object> failureMetadata(RuntimeException error) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        // 取值与 McpToolCaller 保持一致：这是对外约定的可见字符串，不自创词
        metadata.put(ToolMetadata.KEY_TERMINAL, ToolMetadata.TERMINAL_FAILED);
        if (error instanceof JellyfishException) {
            String reason = firstLine(messageOf(error));
            if (!reason.isEmpty()) {
                metadata.put(ToolMetadata.KEY_SUMMARY, reason);
            }
        }
        return Collections.unmodifiableMap(metadata);
    }

    /**
     * 取一段文本的首行并去掉首尾空白。
     * <p>
     * 轨迹行是单行标签，异常消息里的换行（例如带堆栈式描述的消息）不能漏进元数据。
     *
     * @param text 文本，可为 {@code null}
     * @return 首行文本，保证非 {@code null}
     */
    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int newline = text.indexOf('\n');
        return (newline < 0 ? text : text.substring(0, newline)).trim();
    }

    /**
     * 解析工具参数 JSON。
     *
     * @param json 参数 JSON 字符串，可为空
     * @return 参数字典，保证非 {@code null}
     * @throws JellyfishException JSON 非法时抛出
     */
    private static Map<String, Object> parseArguments(String json) {
        if (StringUtils.isBlank(json)) {
            return Collections.emptyMap();
        }
        Map<String, Object> arguments;
        try {
            arguments = ObjectMapperWrapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (RuntimeException e) {
            throw new JellyfishException("工具参数解析失败: " + json, e);
        }
        return arguments == null ? Collections.<String, Object>emptyMap() : arguments;
    }

    /**
     * 取异常的可用消息。
     *
     * @param throwable 异常，可为 {@code null}
     * @return 消息文本；消息为空时退化为类名
     */
    private static String messageOf(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        return StringUtils.isBlank(throwable.getMessage())
                ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    /**
     * 取权限判定理由的可用文本。
     *
     * @param reason 理由，可为 {@code null}
     * @return 理由文本，空时返回固定占位
     */
    private static String messageOf(String reason) {
        return StringUtils.isBlank(reason) ? "未提供理由" : reason;
    }
}
