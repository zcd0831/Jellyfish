package zcd.jellyfish.server.handler;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.server.http.ApiException;
import zcd.jellyfish.server.http.Responses;

/**
 * 路径里的会话标识：先确认它在，再交给业务。
 * <p>
 * <b>为什么每个按会话寻址的端点都要这么做</b>：不校验的话，一个不存在的会话 id 也能让请求「成功」——
 * 命令端点回 {@code 200 + kind=ERROR}、审批端点回 204，调用方无法区分「会话不存在」与「这次没有
 * 待办事项 / 这次没成」，而 {@code GET /sessions/{id}} 对同一种输入回 404。三种答复描述同一件事的
 * 方式不一致，客户端就只能靠猜。
 * <p>
 * 抽成一个类而不是各自写一遍：这条判据在三个处理器里都一样，散着写必然出现「一处 404、一处 200」
 * 这种漂移。它只做「校验 + 取标识」，不碰任何业务状态。
 *
 * @author zcd
 */
final class SessionPath {

    /**
     * 工具类，禁止实例化。
     */
    private SessionPath() {
    }

    /**
     * 校验会话存在，返回其标识。
     *
     * @param sessions  会话域服务，不可为 {@code null}
     * @param sessionId 路径里的会话标识，可为 {@code null}
     * @return 已确认存在的会话标识，保证非空白
     * @throws ApiException 会话不存在时抛出 404
     */
    static String require(SessionManager sessions, String sessionId) {
        try {
            return sessions.require(sessionId).getSessionId();
        } catch (JellyfishException e) {
            throw new ApiException(Responses.NOT_FOUND, "SESSION_NOT_FOUND", "会话不存在：" + sessionId);
        }
    }
}
