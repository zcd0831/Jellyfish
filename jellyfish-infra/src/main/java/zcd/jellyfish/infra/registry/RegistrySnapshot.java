package zcd.jellyfish.infra.registry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 注册表诊断快照：回答「现在谁注册了什么」。
 * <p>
 * 供启动日志与 TUI 的插件视图使用。只有一份表，因此只有一个快照入口：把全部注册项按诊断友好的顺序
 * （类型名 → 路由键 → 注册顺序）摊平成文本，插件作者与运维都不用去猜表结构。
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
        StringBuilder builder = new StringBuilder("registrations:\n");
        for (HandlerRegistration registration : all) {
            render(builder, registration);
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
     */
    private static void render(StringBuilder builder, HandlerRegistration registration) {
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
        builder.append('\n');
    }
}
