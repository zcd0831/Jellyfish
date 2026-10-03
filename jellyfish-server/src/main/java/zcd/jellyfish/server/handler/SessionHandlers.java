package zcd.jellyfish.server.handler;

import io.undertow.server.HttpServerExchange;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.SessionSnapshot;
import zcd.jellyfish.api.extension.SessionUsageSnapshot;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.infra.session.SessionSnapshots;
import zcd.jellyfish.infra.session.SessionUsage;
import zcd.jellyfish.server.ServerConfig;
import zcd.jellyfish.core.conversation.TurnRegistry;
import zcd.jellyfish.server.dto.CancelResult;
import zcd.jellyfish.server.dto.CreateSessionRequest;
import zcd.jellyfish.server.dto.SessionSummary;
import zcd.jellyfish.server.http.ApiException;
import zcd.jellyfish.server.http.JsonBody;
import zcd.jellyfish.server.http.PathParams;
import zcd.jellyfish.server.http.Responses;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * 会话端点的处理器：{@code POST /sessions}、{@code GET /sessions}、{@code GET /sessions/{id}}、
 * {@code DELETE /sessions/{id}}。
 * <p>
 * <b>为什么所有寻址都用 path 里的 id</b>：{@code SessionManager.current()} 是进程级单指针，
 * 在 HTTP 多客户端下不成立；本处理器从不读它，也从不 {@code switchTo}。
 * <p>
 * <b>为什么新建会话要在这里校验 agent / model 存在</b>：内核的 {@code SessionManager.create} 刻意
 * 不做这个校验（它要保证「没配模型」也能建会话）。但接口调用方写了 {@code "model":"gpt-4o"} 这种
 * 不存在的模型时，沉默建会话会让错误推迟到第一次对话才暴露——那时错误信息指向模型路由，
 * 而不是「你在创建时写错了」。因此这里当场校验并回 400。
 * <p>
 * 无状态（只持有协作者），可安全跨线程调用。
 *
 * @author zcd
 */
public final class SessionHandlers {

    /** 运行参数。 */
    private final ServerConfig config;

    /** 会话域服务。 */
    private final SessionManager sessions;

    /** agent 门面，仅用于校验请求体里的 agentId。 */
    private final AgentManager agents;

    /** 模型门面，仅用于校验请求体里的模型。 */
    private final ModelManager models;

    /** 每会话在途回合表，用于取消。 */
    private final TurnRegistry turns;

    /**
     * 构造会话处理器。
     *
     * @param config   运行参数，不可为 {@code null}
     * @param sessions 会话域服务，不可为 {@code null}
     * @param agents   agent 门面，不可为 {@code null}
     * @param models   模型门面，不可为 {@code null}
     * @param turns    在途回合表（内核拥有），不可为 {@code null}
     */
    public SessionHandlers(ServerConfig config, SessionManager sessions, AgentManager agents, ModelManager models,
                           TurnRegistry turns) {
        this.config = config;
        this.sessions = sessions;
        this.agents = agents;
        this.models = models;
        this.turns = turns;
    }

    /**
     * 处理 {@code POST /sessions}：建会话。
     *
     * @param exchange HTTP 交换对象
     * @param params   路径参数（本端点不使用）
     */
    public void create(HttpServerExchange exchange, PathParams params) {
        CreateSessionRequest request = JsonBody.read(exchange, CreateSessionRequest.class, config.getMaxBodyBytes());
        String agentId = text(request == null ? null : request.getAgentId());
        String provider = text(request == null ? null : request.getProvider());
        String model = text(request == null ? null : request.getModel());
        requireAgentExists(agentId);
        requireModelExists(provider, model);
        Session session = sessions.create(agentId, provider, model);
        Responses.writeJson(exchange, Responses.CREATED, SessionSnapshots.capture(session));
    }

    /**
     * 处理 {@code GET /sessions}：会话摘要列表，按最后变更时间倒序。
     *
     * @param exchange HTTP 交换对象
     * @param params   路径参数（本端点不使用）
     */
    public void list(HttpServerExchange exchange, PathParams params) {
        Collection<Session> all = sessions.all();
        List<Session> sorted = new ArrayList<Session>(all);
        sorted.sort(Comparator.comparingLong(Session::getUpdatedAt).reversed());
        List<SessionSummary> summaries = new ArrayList<SessionSummary>(sorted.size());
        for (Session session : sorted) {
            summaries.add(summarize(session));
        }
        Responses.writeJson(exchange, Responses.OK, summaries);
    }

    /**
     * 处理 {@code GET /sessions/{id}}：完整会话快照（含消息）。
     *
     * @param exchange HTTP 交换对象
     * @param params   路径参数（含 {@code id}）
     */
    public void get(HttpServerExchange exchange, PathParams params) {
        Session session = requireSession(params.get("id"));
        Responses.writeJson(exchange, Responses.OK, SessionSnapshots.capture(session));
    }

    /**
     * 处理 {@code DELETE /sessions/{id}}：删除会话（含插件持久化）。
     *
     * @param exchange HTTP 交换对象
     * @param params   路径参数（含 {@code id}）
     */
    public void delete(HttpServerExchange exchange, PathParams params) {
        String sessionId = params.get("id");
        Session deleted = sessions.delete(sessionId);
        if (deleted == null) {
            throw notFound(sessionId);
        }
        Responses.writeNoContent(exchange);
    }

    /**
     * 处理 {@code POST /sessions/{id}/cancel}：取消该会话在途回合。
     * <p>
     * <b>为什么「没取消到」也是 200</b>：该会话此刻可能本来就没有在途回合（用户手快了、或回合刚结束）。
     * 这不是错误而是一个事实，{@code cancelled=false} 如实表达它。
     *
     * @param exchange HTTP 交换对象
     * @param params   路径参数（含 {@code id}）
     */
    public void cancel(HttpServerExchange exchange, PathParams params) {
        Responses.writeJson(exchange, Responses.OK, new CancelResult(turns.cancel(params.get("id"))));
    }

    /**
     * 取会话，不存在即 404。
     *
     * @param sessionId 会话标识
     * @return 会话运行态
     * @throws ApiException 会话不存在时抛出
     */
    private Session requireSession(String sessionId) {
        try {
            return sessions.require(sessionId);
        } catch (JellyfishException e) {
            throw notFound(sessionId);
        }
    }

    /**
     * 构造 404 异常。
     *
     * @param sessionId 会话标识
     * @return 404 异常
     */
    private static ApiException notFound(String sessionId) {
        return new ApiException(Responses.NOT_FOUND, "SESSION_NOT_FOUND", "会话不存在：" + sessionId);
    }

    /**
     * 把会话运行态投影成摘要。
     *
     * @param session 会话运行态
     * @return 摘要
     */
    private static SessionSummary summarize(Session session) {
        SessionUsage usage = session.getUsage();
        SessionUsageSnapshot usageSnapshot = usage == null ? null : new SessionUsageSnapshot(
                usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens(), usage.getLlmCalls(),
                usage.getCacheReadTokens(), usage.getCacheWriteTokens());
        return new SessionSummary(session.getSessionId(), session.getTitle(), session.getAgentId(),
                session.getProvider(), session.getModel(), session.getCreatedAt(),
                session.getUpdatedAt(), session.size(), usageSnapshot);
    }

    /**
     * 把请求体里的取值规整成「给了就是它，空白与未给都是 {@code null}」。
     * <p>
     * 空白归一成 {@code null} 而不是原样透传：{@code ""} 会让内核把它当成「显式指定了空 agent」，
     * 而请求体的语义是「不填即跟随默认」。
     *
     * @param raw 原始取值，可为 {@code null}
     * @return 原取值；为 {@code null} 或空白时返回 {@code null}
     */
    private static String text(String raw) {
        return raw == null || raw.trim().isEmpty() ? null : raw;
    }

    /**
     * 校验 agent 存在。
     *
     * @param agentId agent 标识，可为 {@code null}
     * @throws ApiException agent 不存在时抛出
     */
    private void requireAgentExists(String agentId) {
        if (agentId == null) {
            return;
        }
        try {
            agents.require(agentId);
        } catch (JellyfishException e) {
            throw new ApiException(Responses.BAD_REQUEST, Responses.CODE_BAD_REQUEST,
                    "agent 不存在：" + agentId + "（可用 /agent 查看可用 agent）");
        }
    }

    /**
     * 校验模型存在。
     *
     * @param provider provider 名，可为 {@code null}
     * @param model    模型名，可为 {@code null}
     * @throws ApiException 模型不存在时抛出
     */
    private void requireModelExists(String provider, String model) {
        if (model == null) {
            return;
        }
        try {
            models.resolve(provider, model);
        } catch (JellyfishException e) {
            throw new ApiException(Responses.BAD_REQUEST, Responses.CODE_BAD_REQUEST,
                    "模型不存在：" + provider + "/" + model + "（可用 /model 查看可用模型）");
        }
    }
}
