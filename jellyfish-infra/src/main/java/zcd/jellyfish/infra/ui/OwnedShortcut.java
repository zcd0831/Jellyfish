package zcd.jellyfish.infra.ui;

import zcd.jellyfish.api.extension.ShortcutBinding;

import java.util.Objects;

/**
 * 一条带来源的快捷键绑定：按 {@code order} 升序的收集结果。
 * <p>
 * <b>为什么要带 owner</b>：抢同一个键时内核按 {@code order} 最小者仲裁，而被挤掉的那些要出现在
 * {@code /ui} 的诊断清单里——「我绑的键怎么没反应」必须能查出是谁占了。这与
 * {@link OwnedPanel} 带 owner 是同一个理由。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class OwnedShortcut {

    /** 注册来源（插件标识或 {@code pluginId::子标识}）。 */
    private final String owner;

    /** 绑定。 */
    private final ShortcutBinding binding;

    /**
     * 构造。
     *
     * @param owner   注册来源，不可为空白
     * @param binding 绑定，不可为 {@code null}
     */
    public OwnedShortcut(String owner, ShortcutBinding binding) {
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
        this.binding = Objects.requireNonNull(binding, "binding must not be null");
    }

    /**
     * 获取注册来源。
     *
     * @return owner
     */
    public String getOwner() {
        return owner;
    }

    /**
     * 获取绑定。
     *
     * @return 绑定，保证非 {@code null}
     */
    public ShortcutBinding getBinding() {
        return binding;
    }

    @Override
    public String toString() {
        return "OwnedShortcut{" + binding.getKey() + " -> /" + binding.getCommandName()
                + ", owner=" + owner + '}';
    }
}
