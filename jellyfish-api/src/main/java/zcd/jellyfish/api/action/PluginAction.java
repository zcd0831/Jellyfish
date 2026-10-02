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
     * <p>
     * <b>DONE 承诺什么</b>：消息<b>已经写进会话历史</b>，本回合的下一次模型调用一定看得到它。
     * 它不承诺「模型照做了」——那是回合内容，不是动作的结果。
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
     * <p>
     * <b>DONE 只承诺「已受理」，不承诺「已经压完」</b>：压缩本身是异步的（同一个线程池），
     * 结果请查会话的压缩状态。与既有的 {@code /compact} 完全同口径，外壳因此不需要为它新增任何展示。
     *
     * @param sessionId 目标会话标识，不可为空白
     * @return 动作
     * @throws JellyfishException 会话标识为空白时抛出
     */
    public static PluginAction compact(String sessionId) {
        return new Compact(sessionId);
    }

    /**
     * 切换目标会话的 provider / model。
     * <p>
     * 与用户敲 {@code /model} 改会话模型是同一条路径。它不动权限判定链，也不动历史消息。
     * <p>
     * <b>为什么只在回合边界排空</b>：会话模型是缓存前缀的一部分，回合中途换掉会让本回合后面几轮的
     * 上下文前缀与前面几轮不同源。
     * <p>
     * <b>DONE 承诺什么</b>：会话的模型已经被改掉，本回合剩下的轮次与后续回合都用新的。
     * 变化点本身在回合边界，因此不存在「前几轮一个模型、后几轮另一个模型」的混用。
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
     * 与用户在界面上做分支走同一条路径：新会话是与源会话并列的普通会话（可继续对话、落盘、
     * 被 {@code /resume} 打开），而不是子代理的临时工作区。它是<b>当场落盘</b>的——
     * fork 是一次显式动作，不属于「创建不落盘」那个例外。
     * <p>
     * <b>不把新会话切为当前会话</b>：切换当前会话是外壳的主权，插件替用户跳过去会让屏幕在用户
     * 没操作的情况下换掉。新会话标识写在 {@link ActionHandle#getResult()} 里，
     * 插件也可以订阅 {@code SessionCreatedEvent} 自行跟踪。
     * <p>
     * <b>DONE 承诺什么</b>：新会话<b>已经落盘</b>，它的标识写在 {@link ActionHandle#getResult()} 里
     * （文本形式，供人读；需要编程使用请订阅 {@code SessionCreatedEvent}）。
     * <p>
     * <b>失败会明确回报</b>：源会话不存在、分支点消息找不到、没有可复制的历史、或生命周期钩子
     * 拦下了本次分支时，句柄以 {@link ActionStatus#FAILED} 回报，原因同时落在
     * {@link ActionHandle#getFailureReason()}（机器可读）与 {@link ActionHandle#getResult()}（人可读），
     * 而不是静默无效。
     *
     * @param sessionId 目标会话标识，不可为空白
     * @param messageId 分支点消息标识，可为 {@code null}（表示从末尾分支）
     * @param title     新会话标题，可为 {@code null}（由内核取缺省标题）
     * @return 动作
     * @throws JellyfishException 会话标识为空白时抛出
     */
    public static PluginAction forkSession(String sessionId, String messageId, String title) {
        return new ForkSession(sessionId, messageId, title);
    }

    /**
     * 让内核重建目标会话的工具清单。
     * <p>
     * 工具的可见性在会话首次装配请求时<b>冻结</b>（同一会话内模型每轮看到同一份清单），
     * 因此注册表在会话中途的变化（例如 MCP 重扫后新增的工具）对已有会话不生效；
     * 本动作是让已有会话跟上那些变化的唯一显式入口。
     * <p>
     * <b>只对下一个回合生效</b>：正在跑的回合已经拿过清单，而它每轮都用同一份——中途换掉会让同一个
     * 回合里模型先后看到两套工具，而那正是冻结要挡的东西。若该会话还没装配过请求（没有冻结清单），
     * 下一个回合本就会带上最新工具集，此时没有代价，句柄直接回报成功。
     * <p>
     * <b>它必定换来一次缓存前缀断裂</b>：工具清单在多数厂商的模板里排在 messages 之前，变一个字
     * 则整段请求连同历史一起作废，因此内核会为此记一条 WARN。
     * <p>
     * <b>DONE 承诺什么</b>：分两种情形，都写进 {@link ActionHandle#getResult()}——
     * ① 该会话已有冻结清单时，表示清单已被丢弃、<b>下一个回合</b>会重新冻结（本次会作废一段缓存前缀）；
     * ② 尚未冻结过时，表示本来就没有代价，下一个回合本就会带上最新工具集。
     * 两者都是「已经办好」，差别只在有没有付出缓存代价。
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
