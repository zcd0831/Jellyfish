package zcd.jellyfish.script;

import zcd.jellyfish.api.event.Subscription;

import java.util.Collections;
import java.util.List;

/**
 * 一次脚本注册的结果：登记成功的能力 + 逐条失败原因。
 * <p>
 * <b>为什么要保留 {@link Subscription} 句柄</b>：注册的所有权属于框架（插件停止时按 owner 批量回收），
 * 因此本类<b>不是</b>「用完要关」的凭证。但「提前解除某一条注册」是插件侧本来就有的便利出口，
 * 而按脚本粒度管理能力（停用某个脚本、把它从工具清单里摘出去）只能靠这些句柄。
 * 丢掉它们就等于把「按脚本治理」这件事永久排除在外，因此注册时顺手收好，代价只是一个列表。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ScriptRegistration {

    /** 脚本标识。 */
    private final String scriptId;

    /** 本次登记成功的注册句柄。 */
    private final List<Subscription> subscriptions;

    /** 本次注册失败的原因。 */
    private final List<ScriptIssue> issues;

    /**
     * 构造注册结果。
     *
     * @param scriptId      脚本标识，不可为 {@code null}
     * @param subscriptions 注册句柄，不可为 {@code null}
     * @param issues        失败原因，不可为 {@code null}
     */
    public ScriptRegistration(String scriptId, List<Subscription> subscriptions, List<ScriptIssue> issues) {
        this.scriptId = scriptId;
        this.subscriptions = Collections.unmodifiableList(subscriptions);
        this.issues = Collections.unmodifiableList(issues);
    }

    /**
     * 获取脚本标识。
     *
     * @return 脚本标识
     */
    public String scriptId() {
        return scriptId;
    }

    /**
     * 获取注册句柄。
     *
     * @return 不可变列表
     */
    public List<Subscription> subscriptions() {
        return subscriptions;
    }

    /**
     * 获取注册失败原因。
     *
     * @return 不可变列表
     */
    public List<ScriptIssue> issues() {
        return issues;
    }

    /**
     * 获取登记成功的能力数量。
     *
     * @return 数量
     */
    public int registeredCount() {
        return subscriptions.size();
    }

    @Override
    public String toString() {
        return "ScriptRegistration{scriptId=" + scriptId + ", registered=" + subscriptions.size()
                + ", issues=" + issues.size() + '}';
    }
}
