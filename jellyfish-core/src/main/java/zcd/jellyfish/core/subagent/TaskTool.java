package zcd.jellyfish.core.subagent;

import org.apache.commons.lang3.StringUtils;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.extension.ToolMetadata;
import zcd.jellyfish.api.extension.ToolOutputSink;
import zcd.jellyfish.core.ReActListener;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 工具 {@code task}：把一段自足的子任务委派给子代理，拿回它的最终结论。
 * <p>
 * <b>它只做三件事</b>：把模型给的参数翻成 {@link SubAgentCall}、把子代理的进度转给外壳、
 * 把 {@link SubAgentOutcome} 渲染成回灌文本。所有准入判定与执行编排都在
 * {@link SubAgentLauncher} 里——工具层不重复它们，否则「为什么这次委派被拒绝」会有两份答案。
 * <p>
 * <b>回灌文本首行写结论</b>（与命令行工具同口径）：模型只看正文开头就知道这次委派成没成、
 * 是不是被拒绝了、要不要换个参数重来，不必读完整个报告才能发现它其实什么都没做。
 * <p>
 * <b>进度写进输出捕获通道</b>：子代理的每一步工具调用都会经 {@link ToolOutputSink} 旁路到外壳的
 * 「运行中的工具」区域。刻意<b>不</b>转发子代理的正文增量——那会让界面被两份交织的流刷满，
 * 而用户此刻需要知道的是「它在干活、干到哪一步了」，正文会在结束时整段给出。
 * <p>
 * <b>元数据只带约定键与自己的键</b>：{@link ToolMetadata#KEY_TERMINAL} 用来让界面渲染警示标记
 * （失败与拒绝都不是「正常跑完」），{@link ToolMetadata#KEY_SUMMARY} 让轨迹行上看得出来
 * 「这是哪个子代理、跑了几轮」——本工具写在回灌文本首行的那句是给模型读的，两侧受众不同，
 * 因此各写各的措辞，谁也不解析谁。另外三个键（{@code subagent} / {@code status} / {@code rounds}
 * 与 {@code totalTokens}）是内核不解释、只透传给外壳的结构化事实。
 * <p>
 * 无状态，可安全复用。
 *
 * @author zcd
 */
public final class TaskTool implements ExtensionHandler<ToolCallRequest, ToolCallResult> {

    /** 工具名，也是路由键。 */
    public static final String NAME = "task";

    /** 子代理类型参数名。 */
    private static final String ARG_SUBAGENT_TYPE = "subagent_type";

    /** 人类可读描述参数名。 */
    private static final String ARG_DESCRIPTION = "description";

    /** 任务原文参数名。 */
    private static final String ARG_PROMPT = "prompt";

    /** 元数据键：子代理类型。 */
    private static final String META_SUBAGENT = "subagent";

    /** 元数据键：终态名（小写）。 */
    private static final String META_STATUS = "status";

    /** 元数据键：轮数。 */
    private static final String META_ROUNDS = "rounds";

    /** 元数据键：子代理花掉的 token 总数。 */
    private static final String META_TOKENS = "totalTokens";

    /** 摘要里的类型前缀，让轨迹行一眼能认出这是子代理而不是普通工具。 */
    private static final String SUMMARY_PREFIX = "子代理 ";

    /** 摘要里的 token 单位。 */
    private static final String SUMMARY_TOKEN_UNIT = " tok";

    /** 工具名片：随处理器一起落注册表，模型看到的工具清单里就有它。 */
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(NAME, description(),
            parameters(), required(), false);

    /** 委派器：准入、派生、执行、收尾全在它那里。 */
    private final SubAgentLauncher launcher;

    /**
     * 构造工具。
     *
     * @param launcher 子代理委派器，不可为 {@code null}
     */
    @Inject
    public TaskTool(SubAgentLauncher launcher) {
        this.launcher = Objects.requireNonNull(launcher, "launcher must not be null");
    }

    /**
     * 获取工具名片。
     *
     * @return 工具名片，保证非 {@code null}
     */
    public ToolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ToolCallResult handle(ToolCallRequest request) {
        Map<String, Object> arguments = request.getArguments();
        String agentId = text(arguments.get(ARG_SUBAGENT_TYPE));
        if (StringUtils.isBlank(agentId)) {
            // 参数缺失是 schema 层面的问题，说清楚缺哪个即可；类型写错时由委派器给出可用清单
            return new ToolCallResult(NAME, "[子代理未开始] 缺少参数 subagent_type，"
                    + "请指定一个可委派的子代理类型（见系统提示中的可委派类型列表）。");
        }
        ToolOutputSink sink = request.getOutputSink();
        announce(sink, agentId, text(arguments.get(ARG_DESCRIPTION)));
        SubAgentCall call = new SubAgentCall(request.getSessionId(), agentId, text(arguments.get(ARG_PROMPT)),
                request.getCancellationToken());
        SubAgentOutcome outcome = launcher.run(call, progress(sink));
        return render(outcome, agentId);
    }

    /**
     * 把委派结果渲染成回灌文本与元数据。
     *
     * @param outcome 委派结果
     * @param agentId 子代理类型
     * @return 工具结果，保证非 {@code null}
     */
    private static ToolCallResult render(SubAgentOutcome outcome, String agentId) {
        StringBuilder text = new StringBuilder(headerOf(outcome, agentId));
        if (outcome.hasText() && StringUtils.isNotBlank(outcome.getText())) {
            text.append('\n').append(outcome.getText().trim());
        } else if (StringUtils.isNotBlank(outcome.getError())) {
            text.append('\n').append(outcome.getError());
        }
        return new ToolCallResult(NAME, text.toString(), metadataOf(outcome, agentId));
    }

    /**
     * 组装回灌文本的首行结论。
     *
     * @param outcome 委派结果
     * @param agentId 子代理类型
     * @return 首行文本
     */
    private static String headerOf(SubAgentOutcome outcome, String agentId) {
        switch (outcome.getStatus()) {
            case COMPLETED:
                return "[子代理 " + agentId + " 已完成 · " + outcome.getRounds() + " 轮]";
            case TRUNCATED:
                return "[子代理 " + agentId + " 达到轮数上限 · " + outcome.getRounds() + " 轮，结论不完整]";
            case CANCELLED:
                return "[子代理 " + agentId + " 已取消]";
            case FAILED:
                return "[子代理 " + agentId + " 执行失败]";
            default:
                return "[子代理未开始]";
        }
    }

    /**
     * 组装结构化元数据。
     * <p>
     * 失败与拒绝都要填 {@link ToolMetadata#KEY_TERMINAL}：那个键的约定是「缺省 = 正常跑完」，
     * 而这两种情形都不是。取值本身是工具自己的字符串，内核只做「是不是 COMPLETED」这一个判断。
     *
     * @param outcome 委派结果
     * @param agentId 子代理类型
     * @return 元数据，保证非 {@code null}
     */
    private static Map<String, Object> metadataOf(SubAgentOutcome outcome, String agentId) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put(META_SUBAGENT, agentId);
        metadata.put(META_STATUS, outcome.getStatus().name().toLowerCase());
        metadata.put(META_ROUNDS, Integer.valueOf(outcome.getRounds()));
        metadata.put(META_TOKENS, Long.valueOf(outcome.getUsage().getTotalTokens()));
        metadata.put(ToolMetadata.KEY_SUMMARY, summaryOf(outcome, agentId));
        if (!outcome.hasText()) {
            metadata.put(ToolMetadata.KEY_TERMINAL, outcome.getStatus().name());
        }
        return metadata;
    }

    /**
     * 组装轨迹行上的单行摘要。
     * <p>
     * <b>token 写精确值而不是缩写</b>：状态栏已经有一份「1000 进位缩写」的格式化函数
     * （{@code StatusBarView.abbreviate}），但它在一个内核看不到的位置、且服务于状态栏那种
     * 每帧重画、版面固定的场景。为了这一个小输出把那个函数抽到一个共享模块里，
     * 代价大于收益；而精确值另有一个好处——它可以与 {@code /usage} 里的数字直接对上，
     * 缩写之后就只能“差不多”。
     * <p>
     * <b>零用量时不写</b>：未开始与直接失败的子代理按构造就没有用量，写一个「0 tok」
     * 会让人以为它跑过但没花额度（那两档已有警示后缀在回答「它没跑好」）。
     * <p>
     * <b>只在跑过的情况下写轮数</b>：{@code FAILED} 与 {@code REJECTED} 的轮数按构造就是 0，
     * 而前者的子代理可能已经跑了几轮才抛错——与其写一个不准的数字，不如什么都不说。
     * <p>
     * <b>只给「结论不完整」补一个词</b>：取消 / 失败 / 未开始都会让 {@link ToolMetadata#failed}
     * 为真、从而在界面上多出一个警示后缀，那时再在摘要里写一遍「已取消」就是同一件事说两遍。
     * 而达到轮数上限是唯一一种「没跑好、却不算异常终止」的情形（它确实跑完了，只是没收敛），
     * 因此只有它需要在这里把话说清楚。
     *
     * @param outcome 委派结果
     * @param agentId 子代理类型
     * @return 摘要文本，保证非空
     */
    private static String summaryOf(SubAgentOutcome outcome, String agentId) {
        StringBuilder summary = new StringBuilder(SUMMARY_PREFIX).append(agentId);
        if (outcome.getRounds() > 0) {
            summary.append(" · ").append(outcome.getRounds()).append(" 轮");
        }
        long tokens = outcome.getUsage().getTotalTokens();
        if (tokens > 0L) {
            summary.append(" · ").append(tokens).append(SUMMARY_TOKEN_UNIT);
        }
        if (outcome.getStatus() == SubAgentStatus.TRUNCATED) {
            summary.append(" · 结论不完整");
        }
        return summary.toString();
    }

    /**
     * 在外壳的「运行中的工具」区域写一行开场，让人知道子代理在干什么。
     *
     * @param sink        输出捕获通道
     * @param agentId     子代理类型
     * @param description 人类可读描述，可为 {@code null}
     */
    private static void announce(ToolOutputSink sink, String agentId, String description) {
        sink.write("[" + agentId + "] " + (StringUtils.isBlank(description) ? "开始" : description) + "\n");
    }

    /**
     * 构造子代理回合的进度监听器：每次工具调用写一行。
     * <p>
     * <b>回调运行在委派的同一条线程上</b>（嵌套回合是内联执行的），因此写入顺序与子代理的执行顺序一致。
     * {@link ToolOutputSink#write(String)} 本就要求实现线程安全，这里不额外加锁。
     *
     * @param sink 输出捕获通道
     * @return 监听器，保证非 {@code null}
     */
    private static ReActListener progress(ToolOutputSink sink) {
        return new ReActListener() {
            @Override
            public void onToolCallStarted(String toolCallId, String toolName) {
                sink.write("  · " + toolName + "\n");
            }
        };
    }

    /**
     * 取参数中的文本值。
     *
     * @param value 参数值，可为 {@code null}
     * @return 文本值；非字符串时按 {@code toString} 处理
     */
    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * 组装工具用途描述。
     * <p>
     * 这段文字是模型判断「什么时候该委派」的主要依据，因此写清适用与不适用，而只描述功能
     * 会让它对「一句话就能答完的问题」也派出一个子代理。
     *
     * @return 描述文本
     */
    private static String description() {
        return "把一段自足的子任务委派给子代理执行。子代理在独立上下文中工作：它看不到本次对话的历史、"
                + "也看不到你读过哪些文件，只会看到你写的 prompt，并把最终结论回传给你。"
                + "\n适用：需要大量检索/翻查且只关心结论、需要第二双眼睛复核、或与当前任务无关的独立子任务。"
                + "\n不适用：需要与用户来回确认的任务、依赖本次对话中已形成共识的任务、"
                + "以及一两步就能做完的事——直接自己做更快也更省。"
                + "\nprompt 必须自足：背景、目标、验收标准都要写进去。";
    }

    /**
     * 组装参数 Schema。
     *
     * @return 参数的 JSON Schema properties
     */
    private static Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put(ARG_SUBAGENT_TYPE, string("子代理类型，可取系统提示中列出的可委派类型之一"));
        properties.put(ARG_DESCRIPTION, string("一句话说明这次委派要做什么，供人阅读与日志排查"));
        properties.put(ARG_PROMPT, string("给子代理的完整任务说明。它看不到本次对话，因此必须自足"));
        return properties;
    }

    /**
     * 组装必填参数名。
     *
     * @return 必填参数名列表
     */
    private static List<String> required() {
        return Arrays.asList(ARG_SUBAGENT_TYPE, ARG_PROMPT);
    }

    /**
     * 组装单个字符串参数的 Schema。
     *
     * @param description 参数用途
     * @return 参数 Schema
     */
    private static Map<String, Object> string(String description) {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "string");
        schema.put("description", description);
        return schema;
    }
}
