package zcd.jellyfish.cli;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;

import java.util.Objects;

/**
 * 启动期会话保证：让外壳在进入主流程前一定有一个「当前会话」，并把启动参数里的覆盖项落上去。
 * <p>
 * <b>为什么需要这一步</b>：三种模式的所有智能入口（{@code AgentHarness.chat}）与大部分系统命令
 * （{@code /model} {@code /agent} {@code /mode} {@code /status}）都要求「当前会话」存在，
 * 而会话是纯内存运行态、进程启动时一个都没有。把「建第一个会话」放在 {@code Launcher} 这一层，
 * 三种模式共享同一条规则，模式实现本身不必关心自己是不是第一个。
 * <p>
 * <b>为什么参数校验放在这里而不是解析器</b>：{@code --agent} / {@code --model} 的合法性要靠内核索引判断，
 * 而解析阶段不加载配置（帮助与版本必须能在零配置下工作）。因此「语法」在解析器校验、
 * 「存在性」在这里校验，失败同样是用法错误（退出码 2）。
 * <p>
 * <b>为什么失败要 fail-fast</b>：{@code --model openai/gpt-4o} 打错一个字母时，沉默地按默认模型跑完一轮
 * 再让人去猜「为什么答案不对」代价太高；这里当场报错并提示可用命令。
 *
 * @author zcd
 */
public final class SessionBootstrap {

    /** 会话域服务。 */
    private final SessionManager sessions;

    /** 模型门面，仅用于校验启动参数指定的模型是否存在。 */
    private final ModelManager models;

    /** agent 门面，仅用于校验启动参数指定的 agent 是否存在。 */
    private final AgentManager agents;

    /**
     * 构造启动期会话保证。
     *
     * @param sessions 会话域服务，不可为 {@code null}
     * @param models   模型门面，不可为 {@code null}
     * @param agents   agent 门面，不可为 {@code null}
     */
    public SessionBootstrap(SessionManager sessions, ModelManager models, AgentManager agents) {
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.models = Objects.requireNonNull(models, "models must not be null");
        this.agents = Objects.requireNonNull(agents, "agents must not be null");
    }

    /**
     * 保证当前会话存在，并把启动参数里的覆盖项应用到它上面。
     *
     * @param options 启动参数，不可为 {@code null}
     * @return 当前会话，保证非 {@code null}
     * @throws JellyfishException {@code --session} 指向不存在的会话、或 {@code --agent} / {@code --model} 不存在时抛出
     */
    public Session ensureCurrentSession(StartupOptions options) {
        Objects.requireNonNull(options, "options must not be null");
        requireAgentExists(options);
        requireModelExists(options);
        if (options.getSessionId() != null) {
            return applyOverrides(options, switchTo(options.getSessionId()));
        }
        Session current = sessions.current();
        if (current != null) {
            return applyOverrides(options, current);
        }
        Session created = sessions.create(options.getAgentId(), options.getProvider(), options.getModel(),
                options.getPermissionMode());
        sessions.switchTo(created.getSessionId());
        return created;
    }

    /**
     * 切换到指定会话，失败时给出可读理由。
     *
     * @param sessionId 会话标识
     * @return 目标会话
     * @throws JellyfishException 会话不存在时抛出
     */
    private Session switchTo(String sessionId) {
        try {
            return sessions.switchTo(sessionId);
        } catch (JellyfishException e) {
            // 说明「为什么不存在」：会话是纯内存态，跨进程没有痕迹，这是最容易误解的一点
            throw new JellyfishException(
                    "会话不存在：" + sessionId + "（会话不持久化，单次模式每次进程都是新会话）", e);
        }
    }

    /**
     * 把覆盖项应用到既有会话上。
     * <p>
     * 单次模式下每个进程都是新会话，这条路径主要服务将来的 TUI / Server（同一进程内反复切换会话）；
     * 现在就写出来是为了让「参数含义」在三种模式间完全一致。
     *
     * @param options 启动参数
     * @param session 目标会话
     * @return 目标会话
     */
    private Session applyOverrides(StartupOptions options, Session session) {
        String sessionId = session.getSessionId();
        if (options.getAgentId() != null) {
            sessions.bindAgent(sessionId, options.getAgentId());
        }
        if (options.getModel() != null) {
            sessions.switchModel(sessionId, options.getProvider(), options.getModel());
        }
        if (options.getPermissionMode() != null) {
            sessions.setPermissionMode(sessionId, options.getPermissionMode());
        }
        return session;
    }

    /**
     * 校验启动参数指定的 agent 存在。
     *
     * @param options 启动参数
     * @throws JellyfishException {@code --agent} 指定的 agent 不存在时抛出
     */
    private void requireAgentExists(StartupOptions options) {
        if (options.getAgentId() == null) {
            return;
        }
        try {
            agents.require(options.getAgentId());
        } catch (JellyfishException e) {
            throw new JellyfishException("agent 不存在：" + options.getAgentId() + "（可用 /agent 命令查看可用 agent）", e);
        }
    }

    /**
     * 校验启动参数指定的模型存在。
     *
     * @param options 启动参数
     * @throws JellyfishException {@code --model} 指定的模型不存在时抛出
     */
    private void requireModelExists(StartupOptions options) {
        if (options.getModel() == null) {
            return;
        }
        try {
            models.resolve(options.getProvider(), options.getModel());
        } catch (JellyfishException e) {
            throw new JellyfishException("模型不存在：" + options.getProvider() + "/" + options.getModel()
                    + "（可用 /model 命令查看可用模型）", e);
        }
    }
}
