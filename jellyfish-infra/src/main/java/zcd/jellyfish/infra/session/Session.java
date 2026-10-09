package zcd.jellyfish.infra.session;

import zcd.jellyfish.api.extension.SessionExtensionEntry;
import zcd.jellyfish.api.extension.SessionKind;
import zcd.jellyfish.api.extension.SessionMessageSnapshot;
import zcd.jellyfish.api.extension.SessionSnapshot;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmUsage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次会话的运行态聚合根。
 * <p>
 * 持有三层状态：
 * <ol>
 *     <li><b>标识与生命周期</b>：{@code sessionId}（创建后不可变）、{@code createdAt}、
 *     {@code updatedAt}、{@code title}；</li>
 *     <li><b>会话级选择</b>：当前 {@code agentId}、当前 {@code provider} / {@code model}；</li>
 *     <li><b>内容与计量</b>：消息列表、token 累计、压缩摘要（{@code /compact} 的边界与摘要）。</li>
 * </ol>
 * <b>变更方法一律包级可见</b>：外部只能经 {@link SessionManager} 修改会话，事件广播与将来的持久化
 * 派发都挂在那一个入口上；本类只保证「单会话内的原子性与快照安全」，因此它<b>不感知</b>事件通道、
 * 扩展层与配置。
 * <p>
 * <b>子代理会话靠 {@link #getKind()} 识别</b>：它同样是一个真实会话（有标识、有消息、
 * 有事件），只是不落盘、不进会话列表；{@link #getParentSessionId()} 只是「派生自哪个会话」的
 * 追溯信息，<b>不是判定依据</b>——两者合一曾让分支（fork）会话被误判成子代理会话，
 * 而那意味着它不落盘，即静默的数据丢失。
 * <p>
 * 线程安全策略：所有读写都在实例锁内（方法级 {@code synchronized}），读方法返回防御性快照——
 * 调用方拿到的列表不会被后续追加改动，遍历时也不会出现并发修改。会话之间互不影响，跨会话隔离由
 * {@link SessionManager} 的并发映射提供。
 *
 * @author zcd
 */
public final class Session {

    /** 会话唯一标识，创建后不可变。 */
    private final String sessionId;

    /** 会话创建时间戳（epoch millis），创建后不可变。 */
    private final long createdAt;

    /**
     * 派生该会话的父会话标识，创建后不可变。
     * <p>
     * {@code null} 表示没有「父」；非 {@code null} 时它是<b>追溯信息</b>：子代理会话记录它被
     * 哪次委派生出来，分支会话记录它从哪个会话分出来。
     * <p>
     * <b>它不是判定依据</b>：是不是子代理会话看 {@link #getKind()}。两者合并成一个字段时，
     * 分支会话会被误判成子代理会话，后果是它不落盘——静默的数据丢失。
     */
    private final String parentSessionId;

    /** 会话种类，创建后不可变；它才是「是不是子代理会话」的判定来源。 */
    private final SessionKind kind;

    /** 分支点消息标识（含），非分支会话为 {@code null}。 */
    private final String forkPointMessageId;

    /** 消息列表，按追加顺序排列。 */
    private final List<SessionMessage> messages = new ArrayList<SessionMessage>();

    /**
     * 串行化「捕获快照 + 落盘」的锁，<b>与实例锁刻意分开</b>。
     * <p>
     * <b>为什么不能复用实例锁</b>：落盘会调插件，而插件要写文件、跑 git（{@code GitRepository} 的
     * 超时是 30 秒）；实例锁同时也护着 {@link #getMessages()} 这类读方法，把它们一起挡在一次 git 提交
     * 后面，在界面上就是一次卡顿。因此另起一把锁，只挡「同一会话的另一次落盘」。
     * <p>
     * <b>为什么需要它</b>：两次并发落盘各自「先捕获再写」时，完全可能是「较旧的快照后写」——
     * 磁盘上最后留下的反而是旧的那一份。捕获与派发必须在同一把锁里，落盘顺序才等于状态推进顺序。
     * <p>
     * <b>不参与死锁</b>：唯一的加锁顺序是「本锁 → 实例锁」（{@code underPersistLock} 里调
     * {@link #getMessages()} 一类的同步方法），而实例锁从不会在持有时去要本锁。
     */
    private final Object persistLock = new Object();

    /** 会话标题，可为 {@code null}。 */
    private String title;

    /** 最近一次变更时间戳（epoch millis）。 */
    private long updatedAt;

    /** 当前绑定的 agentId，{@code null} 表示未绑定（fail-open）。 */
    private String agentId;

    /** 当前 provider 名，{@code null} 表示跟随配置默认值。 */
    private String provider;

    /** 当前 model 名，{@code null} 表示跟随配置默认值。 */
    private String model;

    /** token 累计快照，追加时整体替换为新实例。 */
    private SessionUsage usage = SessionUsage.EMPTY;

    /**
     * 压缩摘要，{@code null} 表示从未压缩过。
     * <p>
     * <b>注意它不影响消息列表</b>：压缩是非破坏式的，消息一条不删（屏幕投影、持久化都照旧），
     * 摘要只决定「发给模型的请求从哪里开始」。
     */
    private SessionCompaction compaction;

    /**
     * 扩展条目：完整 key（含 owner 前缀）→ 条目。
     * <p>
     * 用 {@link LinkedHashMap} 是为了让读取顺序稳定：它是「当前有哪些条目」的展示来源，
     * 而一个随哈希变动的顺序会让界面每次刷新都在跳。
     */
    private final Map<String, SessionExtensionEntry> extensionEntries =
            new LinkedHashMap<String, SessionExtensionEntry>();

    /**
     * 构造会话运行态（根会话），仅供 {@link SessionManager} 调用。
     * <p>
     * 它与下面的全参构造器不是重载关系而是便捷入口：绝大多数会话都是普通根会话，
     * 让调用点被迫多写两个 {@code null} 只会把噪音扩散出去。
     *
     * @param sessionId      会话唯一标识
     * @param agentId        初始 agentId，可为 {@code null}
     * @param provider       初始 provider，可为 {@code null}
     * @param model          初始 model，可为 {@code null}
     * @param createdAt      创建时间戳（epoch millis）
     */
    Session(String sessionId, String agentId, String provider, String model, long createdAt) {
        this(sessionId, agentId, provider, model, createdAt, SessionKind.NORMAL, null, null);
    }

    /**
     * 构造会话运行态（含种类与来源），仅供 {@link SessionManager} 调用。
     *
     * @param sessionId       会话唯一标识
     * @param agentId         初始 agentId，可为 {@code null}
     * @param provider        初始 provider，可为 {@code null}
     * @param model           初始 model，可为 {@code null}
     * @param createdAt       创建时间戳（epoch millis）
     * @param kind            会话种类，{@code null} 按 {@link SessionKind#NORMAL} 处理
     * @param parentSessionId 派生该会话的父会话标识，可为 {@code null}
     * @param forkPointMessageId 分支点消息标识，可为 {@code null}
     */
    Session(String sessionId, String agentId, String provider, String model, long createdAt,
            SessionKind kind, String parentSessionId, String forkPointMessageId) {
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.provider = provider;
        this.model = model;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.kind = kind == null ? SessionKind.NORMAL : kind;
        this.parentSessionId = parentSessionId;
        this.forkPointMessageId = forkPointMessageId;
    }

    /**
     * 由快照还原会话运行态，仅供 {@link SessionManager} 在启动期恢复时调用。
     * <p>
     * 直接写入私有字段而不是走 {@code append}：还原是一次「把已有状态放回去」，
     * 不该产生新的时间戳；走变更路径反而会把历史改掉（例如把 {@code updatedAt}
     * 刷成当前时间）。
     *
     * @param snapshot api 侧的会话快照，不可为 {@code null}
     * @return 还原出的会话运行态
     */
    static Session restore(SessionSnapshot snapshot) {
        Session session = new Session(snapshot.getSessionId(), snapshot.getAgentId(), snapshot.getProvider(),
                snapshot.getModel(), snapshot.getCreatedAt(),
                snapshot.getKind(), snapshot.getParentSessionId(), snapshot.getForkPointMessageId());
        session.title = snapshot.getTitle();
        session.updatedAt = snapshot.getUpdatedAt();
        session.usage = SessionSnapshots.toSessionUsage(snapshot.getUsage());
        session.compaction = SessionSnapshots.toCompaction(snapshot.getCompaction());
        for (SessionMessageSnapshot message : snapshot.getMessages()) {
            session.messages.add(SessionSnapshots.toMessage(message));
        }
        for (SessionExtensionEntry entry : snapshot.getExtensionEntries()) {
            session.extensionEntries.put(entry.getKey(), entry);
        }
        return session;
    }

    /**
     * 获取会话唯一标识。
     *
     * @return 会话唯一标识
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取派生该会话的父会话标识。
     * <p>
     * <b>它只是追溯信息，不是判定依据</b>：判定这个会话是不是子代理会话请用 {@link #getKind()}。
     *
     * @return 父会话标识；没有父时返回 {@code null}
     */
    public String getParentSessionId() {
        return parentSessionId;
    }

    /**
     * 获取会话种类。
     *
     * @return 会话种类，保证非 {@code null}
     */
    public SessionKind getKind() {
        return kind;
    }

    /**
     * 获取分支点消息标识。
     *
     * @return 分支点消息标识，非分支会话为 {@code null}
     */
    public String getForkPointMessageId() {
        return forkPointMessageId;
    }

    /**
     * 判断是否为子代理会话。
     * <p>
     * 包级可见：只有 {@link SessionManager} 需要据此决定「落不落盘、进不进列表」；
     * 外部要判断时读 {@link #getKind()}。
     *
     * @return 子代理会话返回 {@code true}
     */
    boolean isEphemeral() {
        return kind == SessionKind.EPHEMERAL;
    }

    /**
     * 获取会话创建时间戳。
     *
     * @return 创建时间戳（epoch millis）
     */
    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * 获取最近一次变更时间戳。
     *
     * @return 变更时间戳（epoch millis），无变更时等于创建时间
     */
    public synchronized long getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 获取会话标题。
     *
     * @return 标题，未设置时为 {@code null}
     */
    public synchronized String getTitle() {
        return title;
    }

    /**
     * 获取当前 agentId。
     *
     * @return agentId，未绑定时为 {@code null}
     */
    public synchronized String getAgentId() {
        return agentId;
    }

    /**
     * 获取当前 provider 名。
     *
     * @return provider 名，跟随默认时为 {@code null}
     */
    public synchronized String getProvider() {
        return provider;
    }

    /**
     * 获取当前 model 名。
     *
     * @return model 名，跟随默认时为 {@code null}
     */
    public synchronized String getModel() {
        return model;
    }

    /**
     * 获取 token 累计快照。
     *
     * @return 累计快照，保证非 {@code null}
     */
    public synchronized SessionUsage getUsage() {
        return usage;
    }

    /**
     * 获取压缩摘要。
     *
     * @return 压缩摘要，从未压缩过时为 {@code null}
     */
    public synchronized SessionCompaction getCompaction() {
        return compaction;
    }

    /**
     * 获取消息条数。
     *
     * @return 消息条数
     */
    public synchronized int size() {
        return messages.size();
    }

    /**
     * 取消息列表的不可修改快照。
     *
     * @return 不可修改列表，可能为空但不会为 {@code null}
     */
    public synchronized List<SessionMessage> getMessages() {
        return Collections.unmodifiableList(new ArrayList<SessionMessage>(messages));
    }

    /**
     * 定位一条消息在会话里的下标。
     * <p>
     * 供压缩边界解析用：边界以 {@code messageId} 记账而不是下标（消息只会追加，但快照跨进程传递，
     * 下标的意义依赖当时的列表，换一处就错）。
     *
     * @param messageId 消息标识，可为 {@code null}
     * @return 下标；{@code null} 或不存在的消息返回 {@code -1}
     */
    public synchronized int indexOfMessage(String messageId) {
        if (messageId == null) {
            return -1;
        }
        for (int index = 0; index < messages.size(); index++) {
            if (messageId.equals(messages.get(index).getMessageId())) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 在实例锁内做一次一致投影。
     * <p>
     * <b>为什么需要它</b>：{@link SessionSnapshots#capture} 是逐个调本类的同步 getter（消息、用量、
     * 更新时间、标题…），而每个 getter 各加一次锁、这些锁之间没有任何东西把「读消息」与「读用量」
     * 绑在一起。别的线程在两次读之间推进过一次状态，快照里就会出现「用量含某条消息、消息列表里
     * 却没有它」这种自相矛盾的一对字段，且它会随落盘留在磁盘上，直到同一会话的下一次落盘才被覆盖。
     * 本方法把这些读放进同一段临界区，因此快照里的字段是同一个瞬间的。
     * <p>
     * <b>这是取快照的唯一入口</b>（`SessionSnapshots.capture` 已收成包私有）：一致投影不能靠
     * 「每个调用方都记得别直接调那个工具方法」来保证——它此前只被修在落盘路径上，而归档、HTTP 响应
     * 与 Spring 查询三处仍在逐个读。收口之后那三处**编译不过**，比断言更硬。
     * <p>
     * <b>为什么放在本类</b>：锁是实例私有的，从外部 lock 属于绕过封装；且「哪些 getter 要一起读」
     * 是与本类状态形状绑定的知识。
     * <p>
     * 与 {@link #underPersistLock(Runnable)} 的加锁顺序一致（落盘锁 → 实例锁），因此调用它不会
     * 引入第二种顺序。
     *
     * @return 会话快照，保证非 {@code null}
     */
    public synchronized SessionSnapshot captureSnapshot() {
        return SessionSnapshots.capture(this);
    }

    /**
     * 测试接缝：在「读完消息、还没读用量」之间执行；缺省什么都不做。
     * <p>
     * <b>为什么需要它</b>：那一段空隙正是「快照里出现自相矛盾的一对字段」唯一的易破处，而它在真实
     * 代码里没有可注入的停顿点（要让**另一个线程**恰好落在两次 getter 之间；同一个线程里的注入在
     * {@code synchronized} 面前是可重入的，区分不出持锁与不持锁）。包私有，只由同包测试设置；
     * 生产路径上它永远是那个 no-op（不参与任何判定，也不改变时序），因此与
     * {@code ActionQueue.betweenWindowAndOffer} 是同一类接缝。
     * <p>
     * <b>为什么是静态而不是实例字段</b>：投影的调用方会拿到 {@code Session} 的 mock（HTTP handler
     * 那类测试只关心响应码与正文），而 mock 实例**不会跑字段初始化**——实例接缝在它上面是
     * {@code null}，于是 `SessionSnapshots.capture` 一调就 NPE。静态字段属于类，与实例怎么来的无关。
     * 代价是它跨用例共享，因此**用完必须立即复位**（本仓库的测试默认顺序执行，不并行）。
     */
    static Runnable betweenSnapshotReads = () -> {
    };

    /**
     * 在「落盘锁」内执行动作，用于串行化「捕获快照 + 交给持久化插件」这一整段。
     * <p>
     * 可见性为包级：只有 {@link SessionManager} 需要它，而且只有它知道哪些变更要落盘。
     *
     * @param action 锁内执行的动作，不可为 {@code null}
     */
    void underPersistLock(Runnable action) {
        synchronized (persistLock) {
            action.run();
        }
    }

    /**
     * 追加一条消息并累加 token 用量。
     * <p>
     * 包级可见：只有 {@link SessionManager} 能改会话，事件广播与将来的落盘派发都在那里统一发生。
     * <p>
     * <b>调用次数只认 assistant 消息</b>：{@code llmCalls} 记的是「调过几次模型」，而只有 assistant
     * 消息是模型响应。user 输入与 tool 结果都是本地产物，它们的用量恒为 {@code null}，若也走
     * {@link SessionUsage#plus(LlmUsage)}，那里的「未返回用量也算一次调用」会把它们各计一次，
     * 于是调用次数涨成消息条数——这个账还会被子代理归集带进父会话
     * （见 {@code SubAgentLauncher#forwardUsage}），使「主会话调了几次模型」同样失真。
     * <p>
     * 因此 assistant 走 {@link SessionUsage#plus(LlmUsage)}（用量缺失时仍计一次真实调用），
     * 其余角色走 {@link SessionUsage#plusTokens(LlmUsage)}（只认用量、不认调用）。
     *
     * @param message 会话消息
     * @return 追加后的消息条数
     */
    synchronized int append(SessionMessage message) {
        messages.add(message);
        usage = LlmMessage.ROLE_ASSISTANT.equals(message.getRole())
                ? usage.plus(message.getUsage())
                : usage.plusTokens(message.getUsage());
        updatedAt = System.currentTimeMillis();
        return messages.size();
    }

    /**
     * 累加一次模型调用的 token 用量（不追加消息）。
     * <p>
     * 给「不产生会话消息的调用」留的口子：{@code /compact} 的摘要调用花的是真金白银的 token，
     * 但它不该在对话里留下一条消息——把它混进消息列表会让屏幕投影多出一条谁也没说过的话。
     * <p>
     * <b>一并刷新 {@code updatedAt}</b>：会话列表按最后变更时间倒序，而「只跑了一次 /compact」
     * 也是一次真实使用；不刷新的话它在列表里的位置会停在最后一次发消息时，
     * 于是「刚从列表里选的会话」不一定排在第一位。
     *
     * @param callUsage 一次调用的用量，可为 {@code null}（厂商未返回时只累加调用次数）
     */
    synchronized void recordUsage(LlmUsage callUsage) {
        usage = usage.plus(callUsage);
        updatedAt = System.currentTimeMillis();
    }

    /**
     * 并入另一份累计用量（不追加消息），用于把子代理回合的花费算到父会话头上。
     * <p>
     * 与 {@link #recordUsage(LlmUsage)} 的差别是「几次调用」：子代理的一个回合可能调了多次模型，
     * 这里把次数一并带过来，而不是把总量当成一次。
     *
     * @param otherUsage 另一份累计用量，可为 {@code null}（按无变化处理）
     */
    synchronized void recordUsage(SessionUsage otherUsage) {
        usage = usage.plus(otherUsage);
        updatedAt = System.currentTimeMillis();
    }

    /**
     * 设置压缩摘要。
     *
     * @param compaction 压缩摘要，可为 {@code null}（表示清除）
     */
    synchronized void setCompaction(SessionCompaction compaction) {
        this.compaction = compaction;
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * 设置会话标题。
     *
     * @param title 标题，可为 {@code null}
     */
    synchronized void setTitle(String title) {
        this.title = title;
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * 设置当前 agentId。
     *
     * @param agentId agentId，可为 {@code null}（表示解绑）
     */
    synchronized void setAgentId(String agentId) {
        this.agentId = agentId;
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * 设置当前 provider / model。
     *
     * @param provider provider 名，可为 {@code null}（表示跟随默认）
     * @param model    model 名，可为 {@code null}（表示跟随默认）
     */
    synchronized void setModel(String provider, String model) {
        this.provider = provider;
        this.model = model;
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * 取扩展条目的不可修改快照。
     *
     * @return 不可修改列表，可能为空但不会为 {@code null}
     */
    public synchronized List<SessionExtensionEntry> getExtensionEntries() {
        return Collections.unmodifiableList(new ArrayList<SessionExtensionEntry>(extensionEntries.values()));
    }

    /**
     * 定位一条扩展条目。
     *
     * @param key 完整 key（含 owner 前缀）
     * @return 条目；不存在时返回 {@code null}
     */
    synchronized SessionExtensionEntry extensionEntry(String key) {
        return extensionEntries.get(key);
    }

    /**
     * 写入（或替换）一条扩展条目。
     * <p>
     * 包级可见：与消息追加同理，只有 {@link SessionManager} 能改会话，
     * 标脏与落盘派发在那一个入口上统一发生。
     *
     * @param entry 条目，不可为 {@code null}
     */
    synchronized void putExtensionEntry(SessionExtensionEntry entry) {
        extensionEntries.put(entry.getKey(), entry);
        updatedAt = System.currentTimeMillis();
    }

    /**
     * 删除一条扩展条目。
     *
     * @param key 完整 key（含 owner 前缀）
     * @return 确实删掉了一条返回 {@code true}
     */
    synchronized boolean removeExtensionEntry(String key) {
        SessionExtensionEntry removed = extensionEntries.remove(key);
        if (removed == null) {
            return false;
        }
        updatedAt = System.currentTimeMillis();
        return true;
    }

    /**
     * 批量放入已存在的消息，<b>不累加它们的 token 用量</b>，也不改变更时间戳。
     * <p>
     * 包级可见，只供 fork 复制历史用：那些消息的用量已经在源会话里记过账，
     * 带过去会把成本重复计入——fork 出来的会话要从零开始计费。
     *
     * @param copied 已存在的消息列表，不可为 {@code null}
     */
    synchronized void copyMessagesWithoutUsage(List<SessionMessage> copied) {
        messages.addAll(copied);
    }
}
