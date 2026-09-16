package zcd.jellyfish.script;

import java.util.Collections;
import java.util.List;

/**
 * 脚本扫描结果：成功加载的脚本 + 逐条问题。
 * <p>
 * <b>为什么问题要作为结果返回而不是抛异常</b>：一个脚本的清单写错，不该让同目录下其它脚本一起失效，
 * 也不该让桥接插件 {@code start()} 失败（那会把「某个脚本写错」放大成「整门语言不可用」）。
 * 因此扫描把「能加载的」与「有问题的」分开收好，由调用方决定怎么呈现——
 * 桥接插件会把问题汇总进启动日志与 {@code /<语言>} 命令的输出里。
 * <p>
 * <b>问题必须被呈现，不能只是丢弃</b>：把有问题的脚本静默跳过，就是本方案反复要避免的静默失效；
 * 作者看到的现象会是「我明明写了脚本，工具却不在清单里」，而没有任何线索。
 * 唯一会抛异常的情形是「脚本根目录存在但不是可读目录」——那属于全局故障，没有脚本能加载。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ScriptScanResult {

    /** 成功加载的脚本，按目录名升序。 */
    private final List<ScriptPlugin> plugins;

    /** 逐条问题。 */
    private final List<ScriptIssue> issues;

    /**
     * 构造扫描结果。
     *
     * @param plugins 成功加载的脚本，不可为 {@code null}
     * @param issues  问题清单，不可为 {@code null}
     */
    public ScriptScanResult(List<ScriptPlugin> plugins, List<ScriptIssue> issues) {
        this.plugins = Collections.unmodifiableList(plugins);
        this.issues = Collections.unmodifiableList(issues);
    }

    /**
     * 获取成功加载的脚本。
     *
     * @return 不可变列表
     */
    public List<ScriptPlugin> plugins() {
        return plugins;
    }

    /**
     * 获取问题清单。
     *
     * @return 不可变列表
     */
    public List<ScriptIssue> issues() {
        return issues;
    }

    /**
     * 判断结果是否既没有脚本也没有问题。
     *
     * @return 两者皆空返回 {@code true}
     */
    public boolean isEmpty() {
        return plugins.isEmpty() && issues.isEmpty();
    }

    @Override
    public String toString() {
        return "ScriptScanResult{plugins=" + plugins.size() + ", issues=" + issues.size() + '}';
    }
}
