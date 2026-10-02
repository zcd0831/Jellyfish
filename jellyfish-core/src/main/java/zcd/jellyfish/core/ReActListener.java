package zcd.jellyfish.core;

import java.util.Map;

/**
 * ReAct 回合的流式回调。
 * <p>
 * <b>它是内核内部接缝，不是外壳接口</b>：外壳只经 {@code ConversationService.submit} +
 * {@code ShellStreams.subscribe} 两条边工作，拿到的是
 * {@code zcd.jellyfish.core.conversation.ShellTurnEvent} 而不是本接口的回调。
 * 本接口现在的直接使用方是：{@code ReActLooper}（驱动回合）、{@code ShellStreams} 的发布器
 * （把回调翻成外壳事件）、{@code InputDirectives}（输入指令的实时输出）、{@code runNested}
 * （子代理回合，它不上外壳事件流），以及 {@code TurnRegistry.releasing}（终态即归还槽位）。
 * <p>
 * <b>线程语义</b>：除 {@link #onToolCallOutput(String, String, String)} 以外，全部回调都在同一个
 * {@code react} 线程上触发，因此实现方不需要自己加锁，也不会出现「文本增量与工具事件乱序」。代价是回调中
 * 做耗时操作会拖住整条循环，需要异步处理的调用方应在实现里自行转投队列。
 * <p>
 * {@code onToolCallOutput} 是刻意的例外：它由工具自己的输出泵线程触发（见该方法的注释），
 * 因为命令行的输出是在「工具还没返回」的过程中产生的，而 {@code react} 线程此刻正阻塞在工具里。
 * 把实时输出排在 {@code react} 线程上就等于把它变成「工具返回后一次性补发」，也就不再是实时输出。
 * <p>
 * 所有方法都是默认空实现：只想拿最终结果的调用方可以只覆盖 {@link #onComplete(ReActResult)}，
 * 或者直接用 {@link #NOOP} 配合 {@link ReActTurn#await()}。
 * <p>
 * <b>元数据为什么要跟着 {@code output} 一起给</b>：{@code output} 是给模型看的文本，
 * 界面要判断「命令成没成」只能去解析它的首行文案——那等于把展示绑死在措辞上。元数据是同一份事实的
 * 结构化版本（见 {@code ToolMetadata}），界面读字段即可。
 *
 * @author zcd
 */
public interface ReActListener {

    /** 什么都不做的监听器，供同步调用方使用。 */
    ReActListener NOOP = new ReActListener() {
    };

    /**
     * 收到一段模型文本增量。
     *
     * @param delta 本次新增的文本片段
     */
    default void onText(String delta) {
    }

    /**
     * 收到一段思考过程增量。
     *
     * @param delta 本次新增的思考过程片段
     */
    default void onThinking(String delta) {
    }

    /**
     * 一次工具调用开始（兼容重载，不带参数）。
     * <p>
     * <b>新实现请覆盖 {@link #onToolCallStarted(String, String, Map)}</b>：本方法只是旧接口的兼容入口，
     * 它拿不到「这条工具在动哪个目标」。切到带参数的版本之后，这里通常不再需要实现。
     *
     * @param toolCallId 工具调用标识
     * @param toolName   工具名
     */
    default void onToolCallStarted(String toolCallId, String toolName) {
    }

    /**
     * 一次工具调用开始（带参数）。
     * <p>
     * <b>它回答的是「这条工具此刻在动哪个目标」</b>：参数由模型生成，是本轮唯一能让界面在
     * 工具<b>返回之前</b>就说明「它在干什么」的输入。没有它，一条要跑几十秒的
     * {@code grep_files} 在屏幕上与「卡死」无法区分。
     * <p>
     * <b>参数是不可信输入</b>：实现方必须自行过滤控制字符（终端把 {@code ESC} 当控制序列引导符），
     * 且必须<b>照原文显示、不要自行脱敏</b>——外壳不知道哪个参数是密钥，按名字猜遮不住真正会出事的地方
     * （命令原文、写入的正文），却会给出「已经打过码了」这种错误的安全感。三处现成的实现
     * （TUI 的审批浮层与轨迹行、{@code -cli} 的 {@code --show-tool-args}）都复用
     * {@code infra/support/ToolArgumentsText} 的同一份渲染，并把这个前提交给使用者（要看得全，
     * 得自己开旗标或展开）。要遮蔽时应当由工具或用户显式声明，而不是由外壳猜。
     * <p>
     * 缺省实现委托到二参版本，因此既有实现不受影响。但反过来不成立：<b>新实现必须覆盖本方法</b>——
     * 只覆盖二参版本会静默丢掉参数。也不能改成「二参默认委托三参」，那会让未改造的既有实现收不到回调。
     *
     * @param toolCallId 工具调用标识
     * @param toolName   工具名
     * @param arguments  工具参数（可能来自模型，不可信），可为 {@code null}（等价空参数）
     */
    default void onToolCallStarted(String toolCallId, String toolName, Map<String, Object> arguments) {
        onToolCallStarted(toolCallId, toolName);
    }

    /**
     * 收到一段工具执行期的输出（可选实现）。
     * <p>
     * <b>线程语义与其它回调不同</b>：它由工具自己的输出泵线程触发（命令行的 stdout 与 stderr 各一条），
     * 因此不在 {@code react} 线程上，且同一时刻可能有多次调用。实现必须线程安全，
     * 并且必须快——它挡在工具与内核之间。
     * <p>
     * <b>这条通道可丢，也必须允许被丢</b>：它只服务于「让用户看到进展」。缓冲满了就丢、
     * 实现抛错会被隔离，都不影响工具结果；但反过来，实现一旦阻塞，子进程的输出会因为管道写满而停住。
     * 因此这里绝不能做落盘、网络请求或等待锁的动作。
     * <p>
     * <b>它不是权威文本</b>：最终落会话与回灌模型的是工具返回的那份结果（经截断中间件处理），
     * 两者可能不一致——这里只是过程。不要把它当作工具结果使用。
     *
     * @param toolCallId 工具调用标识
     * @param toolName   工具名
     * @param chunk      本次新增的输出片段，可能不含换行、可能不是一个完整的行
     */
    default void onToolCallOutput(String toolCallId, String toolName, String chunk) {
    }

    /**
     * 一次工具调用结束（成功或失败都会回调）。
     *
     * @param toolCallId 工具调用标识
     * @param toolName   工具名
     * @param success    是否成功
     * @param output     结果文本（失败时为错误说明）
     * @param metadata   工具结果的结构化元数据，保证非 {@code null}，无元数据时为空映射
     */
    default void onToolCallCompleted(String toolCallId, String toolName, boolean success, String output,
                                     Map<String, Object> metadata) {
    }

    /**
     * 回合正常结束（含达到最大轮次），携带最终结果。
     *
     * @param result 回合结果
     */
    default void onComplete(ReActResult result) {
    }

    /**
     * 回合被调用方取消。
     */
    default void onCancelled() {
    }

    /**
     * 回合在开始前被插件拦下。
     * <p>
     * 与 {@link #onComplete(ReActResult)} / {@link #onCancelled()} / {@link #onError(Throwable)} 互斥：
     * 它发生时用户消息根本没有进会话，模型一次都没被调用。
     * <p>
     * <b>它必须被实现方当成「本回合结束了」</b>：缺省实现委托到 {@link #onCancelled()}，
     * 因此尚未改造的外壳不会把界面永远留在「进行中」。改造过的外壳应当覆盖它，
     * 把理由走自己的提示通道（TUI 的 {@code ShellNotice}、{@code -cli} 的 stderr、Server 的 SSE），
     * <b>而不是把它当作模型的回答渲染出来</b>——它压根不是模型说的。
     *
     * @param reason 拦下的理由，可为 {@code null}
     */
    default void onBlocked(String reason) {
        onCancelled();
    }

    /**
     * 回合因异常终止，与 {@link #onComplete(ReActResult)} / {@link #onCancelled()} 互斥。
     *
     * @param error 失败原因
     */
    default void onError(Throwable error) {
    }
}
