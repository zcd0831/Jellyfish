package zcd.jellyfish.api.action;

import zcd.jellyfish.api.JellyfishException;

import java.util.Objects;

/**
 * 插件主动动作：插件「要求内核做一件事」的唯一载体。
 * <p>
 * <b>为什么要有它</b>：在此之前插件只是能力提供者——内核回头找它（处理器、事件），它不能发起任何事。
 * 这让「检查点、handoff、自动提交」这类工作流参与者做不出来。本类型补上的正是这条边，
 * 但方向是**单向入站队列**：插件只投递一条声明式动作，由内核在定义好的安全点排空执行。
 * <p>
 * <b>插件仍然不能同步回调内核</b>：{@link zcd.jellyfish.api.plugin.PluginContext#submit(PluginAction)}
 * 只入队、立即返回，绝不在提交者的栈上执行。执行结果经 {@link ActionHandle} 轮询取得。
 * 这与既有的「注册是内核回头找插件」正好相反，但两边都不产生同步环。
 * <p>
 * <b>它不是一个事件总线</b>：没有订阅、没有广播、没有处理器注册，只有一条有界的、单向的入站队列。
 * 内核与插件之间的注册与通知仍然只走 {@code ExtensionRegistry} / {@code EventChannel}。
 * <p>
 * <b>会话必须显式给出</b>：插件是进程级的（一次加载、能看到所有会话），「当前会话」对它不是天然概念。
 * 因此每个动作都带 {@code sessionId}，不存在「对当前会话」这种隐含形式。
 * <p>
 * <b>本类不可被插件继承</b>：构造器是包级私有的，唯一的构造途径是下面的静态工厂。
 * 这样一个「有界且逐条列明」的动作清单就不会被插件偷偷扩成开放式接口。
 *
 * @author zcd
 * @see ActionHandle
 * @see DeliverAs
 * @see ActionStatus
 */
public abstract class PluginAction {

    /**
     * 动作种类，与静态工厂一一对应。
     * <p>
     * 它让「动作清单有界」这句话可以被程序检查：内核的排空点按它分派，日志与诊断也按它聚合。
     */
    public enum Kind {

        /** 往会话里投一条用户消息，见 {@link PluginAction#sendUserMessage(String, String, DeliverAs)}。 */
        SEND_USER_MESSAGE,

        /** 立刻压缩会话，见 {@link PluginAction#compact(String)}。 */
        COMPACT,

        /** 中止当前回合，见 {@link PluginAction#abortTurn(String)}。 */
        ABORT_TURN,

        /** 切换会话的 provider / model，见 {@link PluginAction#switchModel(String, String, String)}。 */
        SWITCH_MODEL,

        /** 从某条消息处分支出一个新会话。 */
        FORK_SESSION,

        /** 让内核重建该会话的工具清单。 */
        REBUILD_TOOL_CATALOG
    }

    /** 目标会话标识。 */
    private final String sessionId;

    /**
     * 构造动作。
     *
     * @param sessionId 目标会话标识，不可为空白
     */
    PluginAction(String sessionId) {
        this.sessionId = requireSessionId(sessionId);
    }

    /**
     * 获取动作种类。
     *
     * @return 种类
     */
    public abstract Kind getKind();

    /**
     * 获取目标会话标识。
     *
     * @return 会话标识，保证非空白
     */
    public final String getSessionId() {
        return sessionId;
    }

    /**
     * 投递一条用户消息，由内核在安全点并入会话。
     * <p>
     * <b>这条消息不绕过任何东西</b>：它进入正常回合（或正常的一轮），模型据此产生的工具调用照旧过完整的
     * 权限判定与审批链。动作通道放宽的是「谁能发起」，不是「发起之后能做什么」。
     * <p>
     * <b>{@code null} 或空白的文本没有意义</b>，当场拒绝——一条空消息在会话里会变成需要模型解释的东西，
     * 而不是一次静默的无操作。
     *
     * @param sessionId 目标会话标识，不可为空白
     * @param text      消息文本，不可为空白
     * @param deliverAs 投递方式，不可为 {@code null}
     * @return 动作
     * @throws JellyfishException 会话标识或文本为空白时抛出
     */
    public static PluginAction sendUserMessage(String sessionId, String text, DeliverAs deliverAs) {
        return new SendUserMessage(sessionId, text, deliverAs);
    }

    /**
     * 让内核立刻压缩目标会话。
     * <p>
     * 它与用户敲 {@code /compact} 走同一条路径（同一个线程池、同一套状态轮询与失败上报），
     * 因此外壳不需要为它新增任何展示。
     * <p>
     * <b>只表达「现在就压」，不表达「怎么压」</b>：压缩策略请注册 {@code CompactionStrategy}；
     * 一个带自由文本指示的入口会让「策略」有两个真源。
     *
     * @param sessionId 目标会话标识，不可为空白
     * @return 动作
     * @throws JellyfishException 会话标识为空白时抛出
     */
    public static PluginAction compact(String sessionId) {
        return new Compact(sessionId);
    }

    /**
     * 中止目标会话的在途回合。
     * <p>
     * <b>它不入队</b>：语义就是「置一个取消标志」，是个快动作，因此投递那一刻就执行
     * （与既有的取消语义一致）。因此它在没有在途回合时也**不算失败**——「已经没有回合可中止了」
     * 与「中止成功」的结果相同，为一个已经达成的目标报错只会让插件多写一个无用的分支。
     *
     * @param sessionId 目标会话标识，不可为空白
     * @return 动作
     * @throws JellyfishException 会话标识为空白时抛出
     */
    public static PluginAction abortTurn(String sessionId) {
        return new AbortTurn(sessionId);
    }

    /**
     * 切换目标会话的 provider / model。
     * <p>
     * 与用户敲 {@code /model} 改会话模型是同一条路径。它不动权限判定链，也不动历史消息。
     * <p>
     * <b>为什么只在回合边界排空</b>：会话模型是缓存前缀的一部分，回合中途换掉会让本回合后面几轮的
     * 上下文前缀与前面几轮不同源。
     *
     * @param sessionId 目标会话标识，不可为空白
     * @param provider  provider 名，可为 {@code null}（表示跟随配置默认）
     * @param model     model 名，可为 {@code null}（表示跟随配置默认）
     * @return 动作
     * @throws JellyfishException 会话标识为空白时抛出
     */
    public static PluginAction switchModel(String sessionId, String provider, String model) {
        return new SwitchModel(sessionId, provider, model);
    }

    /**
     * 从会话的某条消息处分支出一个新会话。
     * <p>
     * <b>本期尚未提供</b>：它依赖会话分支能力，排空点会以 {@link ActionStatus#FAILED} 明确回报，
     * 而不是静默无效。
     *
     * @param sessionId 目标会话标识，不可为空白
     * @param messageId 分支点消息标识，可为 {@code null}（表示从末尾分支）
     * @param title     新会话标题，可为 {@code null}
     * @return 动作
     * @throws JellyfishException 会话标识为空白时抛出
     */
    public static PluginAction forkSession(String sessionId, String messageId, String title) {
        return new ForkSession(sessionId, messageId, title);
    }

    /**
     * 让内核重建目标会话的工具清单。
     * <p>
     * <b>本期尚未提供</b>：它依赖工具清单缓存，排空点会以 {@link ActionStatus#FAILED} 明确回报，
     * 而不是静默无效。
     *
     * @param sessionId 目标会话标识，不可为空白
     * @param reason    重建原因，供日志与诊断使用，可为 {@code null}
     * @return 动作
     * @throws JellyfishException 会话标识为空白时抛出
     */
    public static PluginAction rebuildToolCatalog(String sessionId, String reason) {
        return new RebuildToolCatalog(sessionId, reason);
    }

    /**
     * 校验会话标识。
     *
     * @param sessionId 会话标识
     * @return 原值
     */
    private static String requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new JellyfishException("plugin action requires a non-blank sessionId");
        }
        return sessionId;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{sessionId=" + sessionId + '}';
    }

    /**
     * 「投递一条用户消息」动作。
     *
     * @author zcd
     */
    public static final class SendUserMessage extends PluginAction {

        /** 消息文本。 */
        private final String text;

        /** 投递方式。 */
        private final DeliverAs deliverAs;

        /**
         * 构造。
         *
         * @param sessionId 会话标识
         * @param text      消息文本
         * @param deliverAs 投递方式
         */
        private SendUserMessage(String sessionId, String text, DeliverAs deliverAs) {
            super(sessionId);
            if (text == null || text.trim().isEmpty()) {
                throw new JellyfishException("sendUserMessage requires a non-blank text");
            }
            this.text = text;
            this.deliverAs = Objects.requireNonNull(deliverAs, "deliverAs must not be null");
        }

        @Override
        public Kind getKind() {
            return Kind.SEND_USER_MESSAGE;
        }

        /**
         * 获取消息文本。
         *
         * @return 文本，保证非空白
         */
        public String getText() {
            return text;
        }

        /**
         * 获取投递方式。
         *
         * @return 投递方式，保证非 {@code null}
         */
        public DeliverAs getDeliverAs() {
            return deliverAs;
        }

        @Override
        public String toString() {
            return "SendUserMessage{sessionId=" + getSessionId() + ", deliverAs=" + deliverAs + '}';
        }
    }

    /**
     * 「立刻压缩」动作。
     *
     * @author zcd
     */
    public static final class Compact extends PluginAction {

        /**
         * 构造。
         *
         * @param sessionId 会话标识
         */
        private Compact(String sessionId) {
            super(sessionId);
        }

        @Override
        public Kind getKind() {
            return Kind.COMPACT;
        }
    }

    /**
     * 「中止在途回合」动作。
     *
     * @author zcd
     */
    public static final class AbortTurn extends PluginAction {

        /**
         * 构造。
         *
         * @param sessionId 会话标识
         */
        private AbortTurn(String sessionId) {
            super(sessionId);
        }

        @Override
        public Kind getKind() {
            return Kind.ABORT_TURN;
        }
    }

    /**
     * 「切换会话模型」动作。
     *
     * @author zcd
     */
    public static final class SwitchModel extends PluginAction {

        /** provider 名，可为 {@code null}。 */
        private final String provider;

        /** model 名，可为 {@code null}。 */
        private final String model;

        /**
         * 构造。
         *
         * @param sessionId 会话标识
         * @param provider  provider 名
         * @param model     model 名
         */
        private SwitchModel(String sessionId, String provider, String model) {
            super(sessionId);
            this.provider = provider;
            this.model = model;
        }

        @Override
        public Kind getKind() {
            return Kind.SWITCH_MODEL;
        }

        /**
         * 获取 provider 名。
         *
         * @return provider 名，跟随默认时为 {@code null}
         */
        public String getProvider() {
            return provider;
        }

        /**
         * 获取 model 名。
         *
         * @return model 名，跟随默认时为 {@code null}
         */
        public String getModel() {
            return model;
        }
    }

    /**
     * 「分支会话」动作。
     *
     * @author zcd
     */
    public static final class ForkSession extends PluginAction {

        /** 分支点消息标识，可为 {@code null}。 */
        private final String messageId;

        /** 新会话标题，可为 {@code null}。 */
        private final String title;

        /**
         * 构造。
         *
         * @param sessionId 会话标识
         * @param messageId 分支点消息标识
         * @param title     新会话标题
         */
        private ForkSession(String sessionId, String messageId, String title) {
            super(sessionId);
            this.messageId = messageId;
            this.title = title;
        }

        @Override
        public Kind getKind() {
            return Kind.FORK_SESSION;
        }

        /**
         * 获取分支点消息标识。
         *
         * @return 消息标识，未指定时为 {@code null}
         */
        public String getMessageId() {
            return messageId;
        }

        /**
         * 获取新会话标题。
         *
         * @return 标题，未指定时为 {@code null}
         */
        public String getTitle() {
            return title;
        }
    }

    /**
     * 「重建工具清单」动作。
     *
     * @author zcd
     */
    public static final class RebuildToolCatalog extends PluginAction {

        /** 重建原因，可为 {@code null}。 */
        private final String reason;

        /**
         * 构造。
         *
         * @param sessionId 会话标识
         * @param reason    重建原因
         */
        private RebuildToolCatalog(String sessionId, String reason) {
            super(sessionId);
            this.reason = reason;
        }

        @Override
        public Kind getKind() {
            return Kind.REBUILD_TOOL_CATALOG;
        }

        /**
         * 获取重建原因。
         *
         * @return 原因，未指定时为 {@code null}
         */
        public String getReason() {
            return reason;
        }
    }
}
