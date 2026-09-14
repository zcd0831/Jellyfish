package zcd.jellyfish.infra.extension;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ExtensionRequest;

import java.util.Objects;

/**
 * 带来源的处理器绑定：把「谁注册的」与「处理器本身」绑在一起返回给调用点。
 * <p>
 * 只在<b>需要归因</b>的调用点使用（例如权限审计要记录「是哪个插件拦的」）；
 * 普通调用点继续用 {@link ExtensionRegistry#handlers}，拿到的处理器列表更窄，也更少噪音。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @param <C> 请求类型
 * @param <R> 结果类型
 * @author zcd
 */
public final class HandlerBinding<C extends ExtensionRequest<R>, R> {

    /** 来源（内核组件名或 pluginId）。 */
    private final String owner;

    /** 处理器。 */
    private final ExtensionHandler<C, R> handler;

    /**
     * 构造绑定。
     *
     * @param owner   来源（内核组件名或 pluginId），不可为空白
     * @param handler 处理器，不可为 {@code null}
     * @throws JellyfishException 来源为空白时抛出
     */
    public HandlerBinding(String owner, ExtensionHandler<C, R> handler) {
        if (owner == null || owner.trim().isEmpty()) {
            throw new JellyfishException("handler binding owner must not be blank");
        }
        this.owner = owner;
        this.handler = Objects.requireNonNull(handler, "handler must not be null");
    }

    /**
     * 获取来源。
     *
     * @return 来源（内核组件名或 pluginId）
     */
    public String getOwner() {
        return owner;
    }

    /**
     * 获取处理器。
     *
     * @return 处理器
     */
    public ExtensionHandler<C, R> getHandler() {
        return handler;
    }

    @Override
    public String toString() {
        // 处理器常为 lambda，渲染其类型信息没有诊断价值，只保留来源
        return "HandlerBinding{owner=" + owner + '}';
    }
}
