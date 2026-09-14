package zcd.jellyfish.infra.extension;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.extension.ExtensionException;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ExtensionRequest;
import zcd.jellyfish.infra.registry.HandlerRegistration;
import zcd.jellyfish.infra.registry.RegistrySnapshot;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 同步派发策略：只提供「有序查找」与「执行单个处理器」两个原子能力。
 * <p>
 * 与 {@code EventChannel} 共用同一份 {@link TypeRegistry}，区别只在取用方式：本类在调用点线程内联执行，
 * 有返回值、不可丢弃。
 * <p>
 * <b>为什么没有 {@code invoke(request)}</b>：那会把「查找」和「执行」揉在一起，逼着注册表替调用方决定
 * 调用几个处理器、返回哪一个结果。现在的分工是：
 * <ul>
 *     <li>{@link #handlers} / {@link #handler}：只查不调，返回按 {@code order} 升序的处理器；</li>
 *     <li>{@link #invoke}：只调传进来的那一个处理器，返回它的结果；</li>
 *     <li>调用顺序、短路、链式与结果合并全部写在调用点的 {@code for} 循环里。</li>
 * </ul>
 * <b>刻意不做护栏</b>：没有超时、没有白名单、没有异常隔离，因为调用方需要拿到确定结果。
 * 调用点若不能容忍插件阻塞或抛错，必须自行设超时/捕获。
 *
 * @author zcd
 */
public final class ExtensionRegistry {

    /** 共用注册表。 */
    private final TypeRegistry registry;

    /**
     * 构造同步派发策略。
     *
     * @param registry 共用注册表，不可为 {@code null}
     */
    public ExtensionRegistry(TypeRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /**
     * 注册同键唯一的处理器（工具、具名命令）。
     *
     * @param owner      来源（内核组件名或 pluginId），不可为空白
     * @param type       请求类型，不可为 {@code null}
     * @param routeKey   路由键，通常取自请求自身携带的名字
     * @param descriptor 处理器描述符（如工具描述符），可为 {@code null}
     * @param handler    处理器，不可为 {@code null}
     * @param options    注册选项
     * @param <C>        请求类型
     * @param <R>        结果类型
     * @return 注册句柄，关闭后解除本次注册
     * @throws ExtensionException 同键已存在处理器且未声明覆盖时抛出
     */
    public <C extends ExtensionRequest<R>, R> Subscription handle(String owner, Class<C> type, String routeKey,
                                                                  Object descriptor, ExtensionHandler<C, R> handler,
                                                                  RegisterOptions options) {
        Objects.requireNonNull(options, "options must not be null");
        HandlerRegistration registration = registry.registerUnique(owner, type, routeKey, handler, descriptor,
                options.getOrder(), options.isOverride());
        return () -> registry.remove(registration);
    }

    /**
     * 注册类型级贡献：同一请求类型允许 0..N 个处理器。
     *
     * @param owner      来源
     * @param type       请求类型，不可为 {@code null}
     * @param descriptor 处理器描述符，可为 {@code null}
     * @param handler    处理器，不可为 {@code null}
     * @param options    注册选项，{@code order} 在此声明
     * @param <C>        请求类型
     * @param <R>        结果类型
     * @return 注册句柄，关闭后解除本次注册
     */
    public <C extends ExtensionRequest<R>, R> Subscription contribute(String owner, Class<C> type, Object descriptor,
                                                                      ExtensionHandler<C, R> handler,
                                                                      RegisterOptions options) {
        Objects.requireNonNull(options, "options must not be null");
        HandlerRegistration registration = registry.registerShared(owner, type, null, handler, descriptor,
                options.getOrder());
        return () -> registry.remove(registration);
    }

    /**
     * 有序查找全部命中的处理器，并携带来源：只查不调。
     * <p>
     * 语义与 {@link #handlers} 完全一致（类型宽匹配、{@code order} 升序、同序按注册顺序、不可修改、
     * 无命中返回空列表），唯一差别是同时给出 owner，供需要归因的调用点使用：
     * <ul>
     *     <li>类型宽匹配：注册父类型即可被查询子类型命中，路由键为 {@code null} 的类型级注册对所有路由键生效；</li>
     *     <li>不调用任何处理器，也不做任何编排。</li>
     * </ul>
     *
     * @param type     请求类型，不可为 {@code null}
     * @param routeKey 路由键，可为 {@code null}
     * @param <C>      请求类型
     * @param <R>      结果类型
     * @return 不可修改的绑定列表，无命中时为空列表
     */
    @SuppressWarnings("unchecked")
    public <C extends ExtensionRequest<R>, R> List<HandlerBinding<C, R>> bindings(Class<C> type, String routeKey) {
        List<HandlerRegistration> registrations = registry.resolve(type, routeKey);
        List<HandlerBinding<C, R>> bindings = new ArrayList<>(registrations.size());
        for (HandlerRegistration registration : registrations) {
            bindings.add(new HandlerBinding<C, R>(registration.getOwner(),
                    (ExtensionHandler<C, R>) registration.getHandler()));
        }
        return Collections.unmodifiableList(bindings);
    }

    /**
     * 有序查找全部命中的处理器：只查不调。
     * <p>
     * 类型宽匹配（注册父类型即可被查询子类型命中），路由键为 {@code null} 的类型级注册对所有路由键生效；
     * 结果按 {@code order} 升序、同序按注册顺序排列。
     * <p>
     * 与 {@link #bindings} 走同一条查找路径，只是丢掉了 owner；需要归因时改用 {@code bindings}。
     *
     * @param type     请求类型，不可为 {@code null}
     * @param routeKey 路由键，可为 {@code null}
     * @param <C>      请求类型
     * @param <R>      结果类型
     * @return 不可修改的处理器列表，无命中时为空列表
     */
    public <C extends ExtensionRequest<R>, R> List<ExtensionHandler<C, R>> handlers(Class<C> type, String routeKey) {
        List<HandlerBinding<C, R>> bindings = bindings(type, routeKey);
        List<ExtensionHandler<C, R>> handlers = new ArrayList<>(bindings.size());
        for (HandlerBinding<C, R> binding : bindings) {
            handlers.add(binding.getHandler());
        }
        return Collections.unmodifiableList(handlers);
    }

    /**
     * 查找唯一处理器：只查不调。
     * <p>
     * 供「一个名字对应一个实现」的调用点声明自己的前提；0 个即 {@code NO_HANDLER}，
     * 多个即 {@code AMBIGUOUS_HANDLER}——多命中说明该调用点用错了入口（应当改用
     * {@link #handlers}），当场暴露比静默取一个更容易排查。
     *
     * @param type     请求类型，不可为 {@code null}
     * @param routeKey 路由键，可为 {@code null}
     * @param <C>      请求类型
     * @param <R>      结果类型
     * @return 命中的唯一处理器
     * @throws ExtensionException 无命中或多命中时抛出
     */
    public <C extends ExtensionRequest<R>, R> ExtensionHandler<C, R> handler(Class<C> type, String routeKey) {
        List<ExtensionHandler<C, R>> handlers = handlers(type, routeKey);
        if (handlers.isEmpty()) {
            throw new ExtensionException(ExtensionException.Code.NO_HANDLER,
                    "type=" + type.getName() + " routeKey=" + routeKey);
        }
        if (handlers.size() > 1) {
            throw new ExtensionException(ExtensionException.Code.AMBIGUOUS_HANDLER,
                    "type=" + type.getName() + " routeKey=" + routeKey + " matched " + handlers.size());
        }
        return handlers.get(0);
    }

    /**
     * 执行单个处理器：在调用点线程内联调用，结果按请求声明的结果类型校验后原样返回。
     * <p>
     * 不查表、不触碰调用方没有传进来的处理器；处理器抛出的异常原样上抛（受检异常包装为
     * {@link JellyfishException}，因为本方法签名不应声明受检异常）。
     *
     * @param handler 处理器，不可为 {@code null}
     * @param request 请求对象，不可为 {@code null}
     * @param <C>     请求类型
     * @param <R>     结果类型
     * @return 处理器返回的结果
     * @throws ExtensionException 结果类型与 {@link ExtensionRequest#getResultType()} 不符时抛出
     */
    public <C extends ExtensionRequest<R>, R> R invoke(ExtensionHandler<C, R> handler, C request) {
        Objects.requireNonNull(handler, "handler must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Object result;
        try {
            result = handler.handle(request);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new JellyfishException("extension handler failed: " + request, e);
        }
        Class<R> resultType = request.getResultType();
        if (result != null && !resultType.isInstance(result)) {
            throw new ExtensionException(ExtensionException.Code.RESULT_TYPE_MISMATCH,
                    "expected " + resultType.getName() + " but got " + result.getClass().getName()
                            + " for " + request);
        }
        return resultType.cast(result);
    }

    /**
     * 取出某请求类型下的全部描述符。
     *
     * @param type           请求类型，不可为 {@code null}
     * @param descriptorType 期望的描述符类型，不可为 {@code null}
     * @param <D>            描述符类型
     * @return 描述符列表，无描述符时为空列表
     * @throws ExtensionException 存在非空描述符但类型不符时抛出
     */
    public <D> List<D> descriptors(Class<? extends ExtensionRequest<?>> type, Class<D> descriptorType) {
        return registry.descriptorsOf(type, descriptorType);
    }

    /**
     * 按来源批量回收注册。
     *
     * @param owner 来源标识
     * @return 回收的注册数量
     */
    public int unregisterAll(String owner) {
        return registry.removeAll(owner);
    }

    /**
     * 创建诊断快照。
     *
     * @return 快照
     */
    public RegistrySnapshot snapshot() {
        return registry.snapshot();
    }
}
