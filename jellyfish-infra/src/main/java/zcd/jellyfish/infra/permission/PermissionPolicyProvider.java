package zcd.jellyfish.infra.permission;

/**
 * 权限策略来源：把 {@code agentId} 映射成 {@link PermissionPolicy}，是 Permission 模块与 Agent 模块的窄接口。
 * <p>
 * 接口放在消费方所在的 {@code infra/permission}，是刻意的依赖倒置：将来 {@code AgentManager} 从
 * {@code agents.json} 装载 agent 定义后实现本接口，依赖方向是「agent → permission」，
 * 两个包不会互相依赖（先例：{@code RuntimeConfig} 只依赖窄接口 {@code EventPublisher}）。
 * <p>
 * 实现方必须返回不可变快照：一次判定要读「agent 策略 + 只读白名单 + 会话模式」三处状态，
 * 不能读到半更新状态（参照 {@code RuntimeConfig} 的 volatile 快照做法）。
 *
 * @author zcd
 */
@FunctionalInterface
public interface PermissionPolicyProvider {

    /**
     * 取某个 agent 的权限策略。
     *
     * @param agentId agent 标识，可为 {@code null}
     * @return 权限策略，保证非 {@code null}；无策略时返回 {@link PermissionPolicy#unrestricted()}
     */
    PermissionPolicy policyOf(String agentId);
}
