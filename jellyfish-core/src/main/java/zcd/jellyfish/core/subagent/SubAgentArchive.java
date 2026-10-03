package zcd.jellyfish.core.subagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.core.runtime.AgentRunSnapshot;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.config.SubAgentSettings;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionSnapshots;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;
import zcd.jellyfish.infra.tooloutput.ToolOutputStore;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * run 归档：把一次子代理委派的结果与完整 transcript 落盘，供事后回看。
 * <p>
 * <b>为什么需要它</b>：子会话是瞬时的——它不落盘、不进会话列表，{@code SubAgentLauncher} 返回后
 * 就彻底消失。于是「刚才那个子代理到底做了什么、为什么给出这个结论」在委派结束后<b>没有任何出口</b>：
 * 父回合只留下一段最终文本。归档把这段过程留在磁盘上。
 * <p>
 * <b>它是可观测窗口，不是合规归档</b>：受{@link SubAgentSettings#getArchiveKeepFiles() 文件数}与
 * {@link SubAgentSettings#getArchiveMaxBytes() 字节数}上限约束，最旧的会被清理。
 * 需要不可清理的留存时，那该是另一件产品决定（与工具结果落盘同一口径）。
 * <p>
 * <b>为什么复用 {@link ToolOutputStore} 而不是自己写盘</b>：原子改名、路径清洗、按上限清理这几条
 * 规则在「内核往磁盘写运行产物」这件事上只该有一份实现。{@code storeIn(命名空间, …)} 让归档住进
 * 根目录下自己的子目录、用自己的配额——<b>两边的清理互不掏空对方的窗口</b>。
 * <p>
 * <b>写失败不改变委派结果</b>：磁盘问题只记 WARN 并返回 {@code null}。它不该把一次成功的委派
 * 升级成失败——那正是 {@code ToolOutputStore} 既有的失败语义。
 * <p>
 * 无状态（只持有存储与配置门面），可安全跨线程调用。
 *
 * @author zcd
 */
@Singleton
public class SubAgentArchive {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(SubAgentArchive.class);

    /** 归档目录名（落盘根目录下的子目录）。 */
    public static final String NAMESPACE = "subagent-runs";

    /** 落盘存储：归档与工具结果共用同一套写盘规则。 */
    private final ToolOutputStore store;

    /** 运行时配置门面：归档配额现读（{@code /reload} 后即生效）。 */
    private final RuntimeConfig runtimeConfig;

    /**
     * 构造归档器。
     *
     * @param store         落盘存储，不可为 {@code null}
     * @param runtimeConfig 运行时配置门面，不可为 {@code null}
     */
    @Inject
    public SubAgentArchive(ToolOutputStore store, RuntimeConfig runtimeConfig) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.runtimeConfig = Objects.requireNonNull(runtimeConfig, "runtimeConfig must not be null");
    }

    /**
     * 归档一次已终结的 run。
     * <p>
     * 必须在 {@code RunRegistry} 移除该 run 之前调用：run 的身份（父子关系、agent）与终态
     * 只存在快照里，移除之后就再也拿不到了。
     *
     * @param run   run 快照，可为 {@code null}（拿不到就跳过归档）
     * @param child 子会话运行态，可为 {@code null}（拿不到就只落 run 身份与终态）
     * @return 归档文件的绝对路径；跳过或失败时返回 {@code null}
     */
    public String archive(AgentRunSnapshot run, Session child) {
        if (run == null) {
            LOG.warn("run 归档跳过：拿不到快照（run 可能已被移除）");
            return null;
        }
        SubAgentSettings settings = runtimeConfig.getSubAgentSettings();
        try {
            String json = ObjectMapperWrapper.writeValueAsString(documentOf(run, child));
            return store.storeIn(NAMESPACE, run.getRunId(), json, true,
                    settings.getArchiveKeepFiles(), settings.getArchiveMaxBytes());
        } catch (RuntimeException e) {
            LOG.warn("run 归档失败: runId={} reason={}", run.getRunId(), e.toString());
            return null;
        }
    }

    /**
     * 组一份归档文档：run 身份 + 终态 + 用量 + 子会话完整快照。
     * <p>
     * 用 {@link LinkedHashMap} 而不是 DTO 类：这份结构只被写一次、只被外部读，加一层 DTO 只会
     * 让「这里有哪些字段」散到两个文件里。键的顺序固定，因此产物是可读的、也是可 diff 的。
     *
     * @param run   run 快照
     * @param child 子会话运行态，可为 {@code null}
     * @return 文档，保证非 {@code null}
     */
    private static Map<String, Object> documentOf(AgentRunSnapshot run, Session child) {
        Map<String, Object> document = new LinkedHashMap<String, Object>();
        document.put("runId", run.getRunId());
        document.put("parentRunId", run.getParentRunId());
        document.put("rootRunId", run.getRootRunId());
        document.put("parentSessionId", run.getParentSessionId());
        document.put("agentId", run.getAgentId());
        document.put("sessionId", run.getSessionId());
        document.put("toolCallId", run.getToolCallId());
        document.put("status", run.getStatus().name());
        document.put("startedAt", run.getStartedAt());
        document.put("finishedAt", run.getFinishedAt());
        document.put("rounds", run.getRounds());
        document.put("totalTokens", run.getTotalTokens());
        // 会话快照含完整 transcript 与用量明细：它是子代理「做了什么」的唯一原始记录
        document.put("session", child == null ? null : SessionSnapshots.capture(child));
        return document;
    }
}
