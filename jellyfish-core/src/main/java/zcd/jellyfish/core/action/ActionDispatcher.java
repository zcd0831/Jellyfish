package zcd.jellyfish.core.action;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.action.PluginAction;
import zcd.jellyfish.api.extension.CompactionTrigger;
import zcd.jellyfish.core.compact.ConversationCompactor;
import zcd.jellyfish.infra.action.ActionQueue;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.session.SessionManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;

/**
 * 动作执行体：把 {@link ActionQueue} 里排出来的动作翻译成会话域操作。
 * <p>
 * <b>为什么要单独一层</b>：队列必须在 {@code infra}（入队入口是插件上下文），
 * 而执行动作需要核心域的东西——压缩器在 core、消息与会话写入要经 {@code SessionManager}。
 * 因此队列只负责「存、限、取、丢」，执行与回填结果放在这里；两边都不需要知道对方的细节。
 * <p>
 * <b>排空点只有两处</b>，都由 {@code ReActLooper} 在回合内部调用：
 * <ul>
 *     <li>{@link #drainTurnBoundary}：一轮工具批次之后 / 模型本要收敛之前。
 *     压缩、切换模型、以及插入点为「工具批次之后」的用户消息在这里执行——它们都改缓存前缀，
 *     回合中途换掉会让本回合前后几轮的上下文不同源；</li>
 *     <li>{@link #drainConvergence}：模型不再要求工具、本来要收敛那一刻。
 *     插入点为「让回合继续跑」的用户消息在这里执行，返回值告诉循环「不要收敛」。</li>
 * </ul>
 * <p>
 * <b>为什么执行也要在回合内</b>：动作只投进正在跑的回合（见 {@code DeliverAs}），
 * 因此这里没有「起一个新回合」的分支——内核起回合而外壳不知道，会把在途状态、
 * 取消入口与并发写历史三件事一起弄坏。
 * <p>
 * <b>失败不回灌给回合</b>：动作失败只落进它自己的句柄，不影响回合本身。除了压缩与切换模型
 * 这类本来就有自己失败通道的动作，一个坏动作不该让一轮对话发不出去。
 *
 * @author zcd
 */
@Singleton
public class ActionDispatcher {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ActionDispatcher.class);

    /** 动作队列。 */
    private final ActionQueue queue;

    /** 会话域服务：消息写入与模型切换。 */
    private final SessionManager sessionManager;

    /** 压缩器：{@code COMPACT} 动作与 {@code /compact} 走同一条路径。 */
    private final ConversationCompactor compactor;

    /**
     * 构造执行体。
     *
     * @param queue          动作队列，不可为 {@code null}
     * @param sessionManager 会话域服务，不可为 {@code null}
     * @param compactor      压缩器，不可为 {@code null}
     */
    @Inject
    public ActionDispatcher(ActionQueue queue, SessionManager sessionManager,
                           ConversationCompactor compactor) {
        this.queue = queue;
        this.sessionManager = sessionManager;
        this.compactor = compactor;
    }

    /**
     * 登记一个顶层回合，此后该会话可以接收插件动作。
     * <p>
     * 必须在回合作业提交之前调用：与外壳的回合闸门同理，先起回合再登记会让
     * 「起回合」与「第一次投递」之间的动作白跑一趟。
     *
     * @param sessionId    会话标识，不可为空白
     * @param cancelHandle 取消该回合的回调，不可为 {@code null}
     */
    public void beginTurn(String sessionId, Runnable cancelHandle) {
        queue.beginTurn(sessionId, cancelHandle);
    }

    /**
     * 注销顶层回合，并把没来得及排空的动作标为失败。
     *
     * @param sessionId 会话标识，不可为空白
     */
    public void endTurn(String sessionId) {
        queue.endTurn(sessionId);
    }

    /**
     * 排空回合边界上的动作。
     *
     * @param sessionId     会话标识，不可为空白
     * @param canInjectText 本回合是否还有剩余轮次把新消息发给模型；为 {@code false} 时
     *                      用户消息类动作直接失败（否则会在历史里留下一条没人回答的提问）
     * @return 实际注入的用户消息条数
     */
    public int drainTurnBoundary(String sessionId, boolean canInjectText) {
        return execute(sessionId, queue.takeTurnBoundary(sessionId), canInjectText);
    }

    /**
     * 排空收敛点上的动作。
     *
     * @param sessionId     会话标识，不可为空白
     * @param canInjectText 本回合是否还有剩余轮次把新消息发给模型
     * @return 真的注入了消息返回 {@code true}——调用方据此不收敛、继续跑一轮
     */
    public boolean drainConvergence(String sessionId, boolean canInjectText) {
        return execute(sessionId, queue.takeConvergence(sessionId), canInjectText) > 0;
    }

    /**
     * 逐条执行并回填结果。
     * <p>
     * 单条动作抛错不影响后面的动作：插件投的是「建议」，一条坏建议不该把整个排空点弄失败。
     *
     * @param sessionId     会话标识
     * @param pending       待执行动作
     * @param canInjectText 是否还有剩余轮次
     * @return 实际注入的用户消息条数
     */
    private int execute(String sessionId, List<ActionQueue.Pending> pending, boolean canInjectText) {
        int injected = 0;
        for (ActionQueue.Pending entry : pending) {
            try {
                injected += run(sessionId, entry, canInjectText);
            } catch (RuntimeException e) {
                LOG.warn("插件动作执行失败: sessionId={} kind={}", sessionId, entry.getAction().getKind(), e);
                entry.fail("执行失败：" + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        return injected;
    }

    /**
     * 执行单条动作。
     *
     * @param sessionId     会话标识
     * @param entry         待执行动作
     * @param canInjectText 是否还有剩余轮次
     * @return 注入的用户消息条数（0 或 1）
     */
    private int run(String sessionId, ActionQueue.Pending entry, boolean canInjectText) {
        PluginAction action = entry.getAction();
        switch (action.getKind()) {
            case SEND_USER_MESSAGE:
                return inject(sessionId, entry, (PluginAction.SendUserMessage) action, canInjectText);
            case COMPACT:
                return compact(sessionId, entry);
            case SWITCH_MODEL:
                return switchModel(sessionId, entry, (PluginAction.SwitchModel) action);
            case FORK_SESSION:
            case REBUILD_TOOL_CATALOG:
            default:
                // 投递时已经拦过（见 ActionQueue.submit），这里只是不让 switch 有遗漏分支
                entry.fail("本内核尚未提供该能力：" + action.getKind());
                return 0;
        }
    }

    /**
     * 把插件的消息并入会话。
     * <p>
     * <b>它就是一条普通的用户消息</b>：落进历史、参与上下文、下一次模型调用看得到。
     * 它不绕过任何东西——模型据此产生的工具调用照旧走完整的权限判定与审批链。
     * <p>
     * <b>没有剩余轮次时不投</b>：投了也永远不会被发给模型，只会在历史里留下一条没人回答的提问，
     * 而下一次用户输入会与它连成两条 user 消息（Anthropic 直接拒）。
     *
     * @param sessionId     会话标识
     * @param entry         待执行动作
     * @param action        用户消息动作
     * @param canInjectText 是否还有剩余轮次
     * @return 注入成功返回 1，否则 0
     */
    private int inject(String sessionId, ActionQueue.Pending entry, PluginAction.SendUserMessage action,
                       boolean canInjectText) {
        if (!canInjectText) {
            entry.fail("本回合轮次已用尽，消息未投递");
            return 0;
        }
        sessionManager.appendMessage(sessionId, LlmMessage.user(action.getText()), null);
        entry.succeed("已并入本回合作为用户消息");
        return 1;
    }

    /**
     * 发起一次压缩。
     * <p>
     * 与用户敲 {@code /compact} 是同一个入口：同一套校验、同一个线程池、同一份状态供外壳轮询，
     * 因此外壳不需要为插件发起的压缩新增任何展示。
     *
     * @param sessionId 会话标识
     * @param entry     待执行动作
     * @return 恒为 0（压缩不注入消息）
     */
    private int compact(String sessionId, ActionQueue.Pending entry) {
        if (!compactor.isAvailable()) {
            entry.fail("压缩不可用：没有任何插件提供压缩策略");
            return 0;
        }
        compactor.start(sessionId, CompactionTrigger.MANUAL);
        entry.succeed("已发起压缩，结果请查看会话的压缩状态");
        return 0;
    }

    /**
     * 切换会话模型。
     *
     * @param sessionId 会话标识
     * @param entry     待执行动作
     * @param action    切换模型动作
     * @return 恒为 0（切换模型不注入消息）
     */
    private int switchModel(String sessionId, ActionQueue.Pending entry, PluginAction.SwitchModel action) {
        sessionManager.switchModel(sessionId, action.getProvider(), action.getModel());
        entry.succeed("已切换会话模型");
        return 0;
    }
}
