package zcd.jellyfish.infra.plugin;

import zcd.jellyfish.api.RuntimeInfo;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 运行时信息持有者：外壳在启动期写入一次，插件上下文随时读快照。
 * <p>
 * <b>为什么需要「持有者」而不是直接注入值</b>：装配根要构造插件上下文工厂，而外壳种类只有到
 * {@code Launcher} 选完模式才知道——那时 DI 装配早就结束了。用一个引用稳定的可变持有者，
 * 装配顺序与「值什么时候确定」就解耦了。
 * <p>
 * <b>写入必须早于内核 bootstrap</b>：插件在 {@code start()} 里就会读它
 * （例如「没有审批通道就不注册需要写权限的工具」），因此 {@code Launcher} 在 bootstrap 之前写入，
 * 保证插件读到的是最终值而不是缺省值。这条时序有测试锁定。
 * <p>
 * <b>缺省值是「未知外壳」</b>：不经过外壳启动流程的用法（单元测试、嵌入式集成）拿到的是
 * {@link RuntimeInfo#unknown()} 而不是 {@code null}——插件不必到处判空，也不必被迫伪造一个外壳。
 * <p>
 * 线程安全：写入与读取可能分处不同线程，且读取发生在插件的任意线程上。
 *
 * @author zcd
 */
@Singleton
public class RuntimeInfoHolder {

    /** 当前快照；缺省为「未知外壳」。 */
    private final AtomicReference<RuntimeInfo> current = new AtomicReference<RuntimeInfo>(RuntimeInfo.unknown());

    /**
     * 构造持有者，初值为「未知外壳」。
     */
    @Inject
    public RuntimeInfoHolder() {
    }

    /**
     * 写入快照。
     * <p>
     * 允许在进程生命周期内重复写入：外壳种类不变，但「有没有终端」这类进程级事实理论上可能变
     * （进程被重新挂上终端），覆盖写比让调用方自己判断更简单。
     *
     * @param info 快照，不可为 {@code null}
     */
    public void set(RuntimeInfo info) {
        current.set(Objects.requireNonNull(info, "info must not be null"));
    }

    /**
     * 读取当前快照。
     *
     * @return 快照，保证非 {@code null}；未写入过时为 {@link RuntimeInfo#unknown()}
     */
    public RuntimeInfo snapshot() {
        return current.get();
    }
}
