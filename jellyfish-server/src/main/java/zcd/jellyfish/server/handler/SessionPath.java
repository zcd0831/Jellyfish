package zcd.jellyfish.server.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.server.http.ApiException;
import zcd.jellyfish.server.http.LogText;
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

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(SessionPath.class);

    /**
     * 工具类，禁止实例化。
     */
    private SessionPath() {
    }

    /**
     * 校验会话存在，取出运行态。
     *
     * @param sessions  会话域服务，不可为 {@code null}
     * @param sessionId 路径里的会话标识，可为 {@code null}
     * @return 会话运行态
     * @throws ApiException 会话不存在时抛出 404
     */
    static Session require(SessionManager sessions, String sessionId) {
        try {
            return sessions.require(sessionId);
        } catch (JellyfishException e) {
            // 回给调用方的文案不带 id（理由见 notFound），原文只留在这里
            LOG.info("请求的会话不存在: {}", LogText.singleLine(sessionId));
            throw notFound();
        }
    }

    /**
     * 构造「会话不存在」的 404。
     * <p>
     * <b>文案里刻意不带会话标识</b>：调用方本来就知道自己请求的是哪个 id（那是它自己给的），而这段
     * 文案会进客户端、进日志、进代理与监控——带上未经清洗的 id 只是多一个注入面（换行能伪造日志行、
     * {@code ESC} 能改终端显示）。另一条同样要求它不含调用方输入的原因是「存在但无权」与
     * 「不存在」必须<b>逐字节相同</b>：只要文案里出现 id，两侧就可能在被规范化后出现差异。
     *
     * @return 404 异常，文案为固定文本
     */
    static ApiException notFound() {
        return new ApiException(Responses.NOT_FOUND, "SESSION_NOT_FOUND", "会话不存在");
    }
}
