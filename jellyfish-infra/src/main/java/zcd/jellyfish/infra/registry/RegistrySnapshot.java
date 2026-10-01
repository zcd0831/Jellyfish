package zcd.jellyfish.infra.registry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 注册表诊断快照：回答「现在谁注册了什么」。
 * <p>
 * 供启动日志与 TUI 的插件视图使用。只有一份表，因此只有一个快照入口：把全部注册项按诊断友好的顺序
 * （类型名 → 路由键 → 注册顺序）摊平成文本，插件作者与运维都不用去猜表结构。
 * <p>
 * <b>被覆盖压住的层也会列出来并标 {@code (shadowed)}</b>：覆盖链让「同键唯一」变成
 * 「有一个生效、其余被压住」，只显示生效项会让「谁被顶掉了」无从查起——而那正是覆盖这类问题
 * 最难排查的地方。
 * <p>
 * 快照在创建时刻即固定文本内容，不影响后续注册行为。
 *
 * @author zcd
 */
public final class RegistrySnapshot {

    /** 渲染文本。 */
    private final String rendered;

    /**
     * 构造快照。
     *
     * @param rendered 渲染文本
     */
    private RegistrySnapshot(String rendered) {
        this.rendered = rendered;
    }

    /**
     * 从注册表生成快照。
     *
     * @param registry 注册表，不可为 {@code null}
     * @return 诊断快照
     */
    public static RegistrySnapshot of(TypeRegistry registry) {
        List<HandlerRegistration> all = new ArrayList<>(registry.registrations());
        all.sort(Comparator.comparing((HandlerRegistration registration) -> registration.getType().getName())
                .thenComparing(registration -> registration.getRouteKey() == null ? "" : registration.getRouteKey())
                .thenComparingLong(HandlerRegistration::getSequence));
        if (all.isEmpty()) {
            return new RegistrySnapshot("");
        }
        Set<HandlerRegistration> active = registry.activeRegistrations();
        StringBuilder builder = new StringBuilder("registrations:\n");
        for (HandlerRegistration registration : all) {
            render(builder, registration, active.contains(registration));
        }
        return new RegistrySnapshot(builder.toString());
    }

    /**
     * 判断快照是否为空。
     *
     * @return 注册表为空时返回 {@code true}
     */
    public boolean isEmpty() {
        return rendered.isEmpty();
    }

    /**
     * 渲染为多行文本。
     *
     * @return 可读快照，无注册时返回空串
     */
    public String render() {
        return rendered;
    }

    /**
     * 渲染单条注册项。
     *
     * @param builder      输出缓冲
     * @param registration 注册项
     * @param active       本条此刻是否生效（被覆盖压住的层为 {@code false}）
     */
    private static void render(StringBuilder builder, HandlerRegistration registration, boolean active) {
        builder.append("  ").append(registration.getType().getSimpleName()).append("  ")
                .append(registration.getRouteKey() == null ? "<type-wide>" : registration.getRouteKey())
                .append("  order=").append(registration.getOrder())
                .append("  <- ").append(registration.getOwner());
        if (registration.getDescriptor() != null) {
            builder.append("  descriptor=").append(registration.getDescriptor().getClass().getSimpleName());
        }
        if (registration.getOverriddenOwner() != null) {
            builder.append("  (overrides ").append(registration.getOverriddenOwner()).append(')');
        }
        // 被压住的层也列出来：只显示生效项会让「谁被顶掉了」无从查起，而覆盖正是最难排查的一类问题
        if (!active) {
            builder.append("  (shadowed)");
        }
        builder.append('\n');
    }
}
