package zcd.jellyfish.cli;

import org.mockito.Mockito;
import zcd.jellyfish.api.event.EventPublisher;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionManager;

/**
 * 测试支撑：造一个真实的会话运行态。
 * <p>
 * {@link Session} 的构造器是包级可见的（外部只能经 {@link SessionManager} 修改会话），而它是会话域的
 * 聚合根、不是「可以随手 new 出来的值对象」。因此外壳测试既不能直接构造它，也不该把它 mock 成假对象——
 * 这里用一个<b>真的</b> {@code SessionManager} 造出真会话，只把它的两个协作者换成哑实现。
 *
 * @author zcd
 */
public final class SessionTestSupport {

    private SessionTestSupport() {
    }

    /**
     * 创建一个真实会话（无 agent、无模型、常规权限模式）。
     *
     * @return 会话运行态，保证非 {@code null}
     */
    public static Session newSession() {
        return newSessionManager().createDefault();
    }

    /**
     * 创建一个真实的会话域服务（协作者为哑实现）。
     * <p>
     * 外壳测试需要「{@code switchTo} 之后 {@code current} 真的变了」这种真实行为，
     * mock 一个会话表反而会把接线错误掩盖掉。
     *
     * @return 会话域服务，保证非 {@code null}
     */
    public static SessionManager newSessionManager() {
        EventPublisher silentPublisher = event -> {
            // 会话事件在外壳测试里没有订阅者，发出去也没人听
        };
        return new SessionManager(Mockito.mock(AgentManager.class), silentPublisher);
    }
}
