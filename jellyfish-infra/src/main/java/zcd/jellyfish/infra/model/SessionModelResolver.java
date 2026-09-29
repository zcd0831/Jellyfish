package zcd.jellyfish.infra.model;

import org.apache.commons.lang3.StringUtils;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.config.AgentDefinition;
import zcd.jellyfish.infra.session.Session;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Objects;

/**
 * 会话模型解析：给定一个会话，回答「这一轮该用哪个模型」。
 * <p>
 * 解析顺序固定为三级：
 * <ol>
 *     <li><b>会话显式选定</b>（{@code /model} 改过）——用户的直接指令优先于一切；</li>
 *     <li><b>该 agent 的偏好模型</b>（{@code agents.json} 的 {@code model} 字段）——绑定 agent
 *     就应当连它的模型一起生效，否则那个字段只是装饰；</li>
 *     <li><b>全局默认</b>（{@code models.json} 的 {@code defaultProvider} / {@code defaultModel}）。</li>
 * </ol>
 * <b>为什么单独一个类</b>：这条三级回落原本被写了两遍——{@code ReActLooper} 一份、
 * {@code ConversationCompactor} 一份，靠注释声明「刻意保持镜像」。而两者的判据必须相同：
 * 压缩要按同一个模型的窗口裁剪、花同一个模型的额度，一旦两边漂移，
 * 摘要就会以另一个模型的上下文长度去衡量对话。与其靠约定，不如让它变成一次调用。
 * <p>
 * <b>子代理会话自然落到第 2 级</b>：子会话创建时 provider / model 都是 {@code null}，
 * 但它的 {@code agentId} 指向被委派的那个 agent，因此自动用上该 agent 的偏好模型；
 * 没配则落到全局默认。刻意<b>不</b>继承父会话的模型——子代理与主会话除了传入的任务之外相互隔离。
 * <p>
 * <b>解析不到时的失败语义</b>：不返回 {@code null}，而是上抛。三个来源都是「用户明确表达过的意图」，
 * 一个都落不到实处时静默降级只会让问题在更远的地方以更难懂的形式出现。
 *
 * @author zcd
 */
@Singleton
public class SessionModelResolver {

    /** 模型门面：名字 → Provider/Model 的解析与路由。 */
    private final ModelManager modelManager;

    /** agent 门面：取会话所绑 agent 的偏好模型。 */
    private final AgentManager agentManager;

    /**
     * 构造会话模型解析器。
     *
     * @param modelManager 模型门面，不可为 {@code null}
     * @param agentManager agent 门面，不可为 {@code null}
     */
    @Inject
    public SessionModelResolver(ModelManager modelManager, AgentManager agentManager) {
        this.modelManager = Objects.requireNonNull(modelManager, "modelManager must not be null");
        this.agentManager = Objects.requireNonNull(agentManager, "agentManager must not be null");
    }

    /**
     * 解析会话这一轮应当使用的模型。
     *
     * @param session 会话运行态，不可为 {@code null}
     * @return 解析结果，保证非 {@code null}
     * @throws zcd.jellyfish.api.JellyfishException 三个来源都解析不到时抛出
     */
    public ResolvedModel resolve(Session session) {
        Objects.requireNonNull(session, "session must not be null");
        String provider = session.getProvider();
        String model = session.getModel();
        if (!StringUtils.isAnyBlank(provider, model)) {
            return modelManager.resolve(provider, model);
        }
        return resolveByAgentOrDefault(session.getAgentId());
    }

    /**
     * 按 agentId 解析偏好模型，没有则回落到全局默认。
     * <p>
     * 给「还没有会话、但已经知道要用哪个 agent」的调用点留的口子（例如委派前的预校验），
     * 它保证「预校验用哪个模型」与「真正跑起来用哪个模型」是同一份判据。
     *
     * @param agentId agent 标识，可为 {@code null}
     * @return 解析结果，保证非 {@code null}
     * @throws zcd.jellyfish.api.JellyfishException 偏好模型解析不到且没有可用默认模型时抛出
     */
    public ResolvedModel resolveByAgentOrDefault(String agentId) {
        AgentDefinition definition = agentManager.find(agentId);
        String preferred = definition == null ? null : definition.getModel();
        if (StringUtils.isNotBlank(preferred)) {
            return modelManager.resolveReference(preferred);
        }
        return modelManager.resolveDefault();
    }
}
