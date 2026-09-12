package zcd.jellyfish.infra.event.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.command.Command;
import zcd.jellyfish.api.event.command.CommandException;
import zcd.jellyfish.api.event.command.CommandHandler;
import zcd.jellyfish.api.event.command.PluginExtensible;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 细粒度命令注册表：按 (命令类型, 路由键) 维护唯一处理器，支持覆盖与按来源回收。
 * <p>
 * 所有校验都在注册期 fail-fast：
 * <ul>
 *     <li>键冲突且未声明 {@link RegisterOptions#override(boolean)} 直接抛错；</li>
 *     <li>插件注册未标记 {@link PluginExtensible} 的命令类型直接抛错，防止绕过核心命令；</li>
 *     <li>覆盖成功时记录被覆盖的来源，供启动日志与诊断快照使用。</li>
 * </ul>
 * 解析期按运行时类收集候选并缓存，命中 0 个抛 {@code NO_HANDLER}，命中多于 1 个抛 {@code AMBIGUOUS_HANDLER}。
 *
 * @author zcd
 */
public final class CommandRegistry {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(CommandRegistry.class);

    /** 注册项：键 → 唯一处理器。 */
    private final Map<CommandKey, CommandRegistration> handlers = new ConcurrentHashMap<>();

    /** 解析缓存：命令运行时类 → 候选注册项（已按注册顺序排序）。 */
    private final Map<Class<?>, List<CommandRegistration>> candidates = new ConcurrentHashMap<>();

    /** 注册序号，保证候选顺序稳定。 */
    private final AtomicLong sequence = new AtomicLong();

    /**
     * 注册命令处理器。
     *
     * @param owner       来源（内置组件名或 pluginId），用于诊断与回收
     * @param fromPlugin  是否来自插件
     * @param commandType 命令类型，不可为 {@code null}
     * @param routeKey    路由键，{@code null} 表示类型唯一
     * @param handler     命令处理器，不可为 {@code null}
     * @param options     注册选项，不可为 {@code null}
     * @return 注册句柄，关闭后解除本次注册
     * @throws JellyfishException 键冲突且未声明覆盖，或插件注册了未开放的命令类型时抛出
     */
    public <C extends Command<R>, R> Subscription register(String owner, boolean fromPlugin, Class<C> commandType,
                                                           String routeKey, CommandHandler<C, R> handler,
                                                           RegisterOptions options) {
        Objects.requireNonNull(commandType, "commandType must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        Objects.requireNonNull(options, "options must not be null");
        if (fromPlugin && !commandType.isAnnotationPresent(PluginExtensible.class)) {
            throw new JellyfishException("plugin is not allowed to register command type: " + commandType.getName());
        }
        @SuppressWarnings("unchecked")
        Class<? extends Command<?>> type = (Class<? extends Command<?>>) (Class<?>) commandType;
        CommandKey key = CommandKey.of(type, routeKey);
        String existingOwner = put(key, owner, type, routeKey, handler, fromPlugin, options);
        candidates.clear();
        if (existingOwner != null) {
            LOG.info("命令处理器被覆盖: {} 从 {} 改为 {}", key, existingOwner, owner);
        }
        return () -> unregister(key, owner);
    }

    /**
     * 解析命令对应的处理器。
     *
     * @param command 命令对象，不可为 {@code null}
     * @return 唯一命中的处理器
     * @throws CommandException 命中 0 个（{@code NO_HANDLER}）或多于 1 个（{@code AMBIGUOUS_HANDLER}）时抛出
     */
    public CommandHandler<?, ?> resolve(Command<?> command) {
        Objects.requireNonNull(command, "command must not be null");
        String routeKey = command.getRouteKey();
        CommandRegistration matched = null;
        for (CommandRegistration candidate : candidatesFor(command.getClass())) {
            if (!matchesRoute(candidate, routeKey)) {
                continue;
            }
            if (matched != null) {
                throw new CommandException(CommandException.Code.AMBIGUOUS_HANDLER,
                        "type=" + command.getClass().getName() + " routeKey=" + routeKey
                                + " candidates=[" + matched.getOwner() + ", " + candidate.getOwner() + ']');
            }
            matched = candidate;
        }
        if (matched == null) {
            throw new CommandException(CommandException.Code.NO_HANDLER,
                    "type=" + command.getClass().getName() + " routeKey=" + routeKey);
        }
        return matched.getHandler();
    }

    /**
     * 按来源批量回收注册。
     *
     * @param owner 来源标识
     * @return 回收的注册数量
     */
    public int unregisterAll(String owner) {
        int removed = 0;
        for (Map.Entry<CommandKey, CommandRegistration> entry : handlers.entrySet()) {
            if (Objects.equals(owner, entry.getValue().getOwner()) && handlers.remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }
        if (removed > 0) {
            candidates.clear();
        }
        return removed;
    }

    /**
     * 判断注册表是否为空。
     *
     * @return 无任何注册返回 {@code true}
     */
    public boolean isEmpty() {
        return handlers.isEmpty();
    }

    /**
     * 清空注册表，用于总线关闭时释放引用。
     */
    public void clear() {
        handlers.clear();
        candidates.clear();
    }

    /**
     * 渲染诊断视图。
     *
     * @return 多行文本，无注册时返回空串
     */
    public String render() {
        if (isEmpty()) {
            return "";
        }
        List<CommandKey> keys = new ArrayList<>(handlers.keySet());
        keys.sort(Comparator.comparing((CommandKey key) -> key.getCommandType().getName())
                .thenComparing(key -> key.getRouteKey() == null ? "" : key.getRouteKey()));
        StringBuilder builder = new StringBuilder("commands:\n");
        for (CommandKey key : keys) {
            CommandRegistration registration = handlers.get(key);
            if (registration == null) {
                continue;
            }
            builder.append("  ").append(key.getCommandType().getSimpleName()).append("  ")
                    .append(key.getRouteKey() == null ? "<type-unique>" : key.getRouteKey())
                    .append("  <- ").append(registration.getOwner());
            if (registration.getOverriddenOwner() != null) {
                builder.append(" (overrides ").append(registration.getOverriddenOwner()).append(')');
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    /**
     * 写入注册项，处理冲突与覆盖。
     *
     * @param key         命令键
     * @param owner       来源
     * @param commandType 命令类型
     * @param routeKey    路由键
     * @param handler     处理器
     * @param fromPlugin  是否来自插件
     * @param options     注册选项
     * @return 被覆盖的来源，未覆盖时为 {@code null}
     * @throws JellyfishException 键冲突且未声明覆盖时抛出
     */
    private String put(CommandKey key, String owner, Class<?> commandType, String routeKey,
                       CommandHandler<?, ?> handler, boolean fromPlugin, RegisterOptions options) {
        while (true) {
            CommandRegistration existing = handlers.get(key);
            if (existing == null) {
                CommandRegistration fresh = new CommandRegistration(owner, commandType, routeKey, handler,
                        fromPlugin, sequence.incrementAndGet(), null);
                if (handlers.putIfAbsent(key, fresh) == null) {
                    return null;
                }
                continue;
            }
            if (!options.isOverride()) {
                throw new JellyfishException("command already registered: " + key + " by " + existing.getOwner());
            }
            CommandRegistration replacement = new CommandRegistration(owner, commandType, routeKey, handler,
                    fromPlugin, sequence.incrementAndGet(), existing.getOwner());
            if (handlers.replace(key, existing, replacement)) {
                return existing.getOwner();
            }
        }
    }

    /**
     * 解除一次注册，仅当当前注册项正是本次注册时生效。
     *
     * @param key   命令键
     * @param owner 注册时的来源
     */
    private void unregister(CommandKey key, String owner) {
        CommandRegistration existing = handlers.get(key);
        if (existing != null && Objects.equals(owner, existing.getOwner()) && handlers.remove(key, existing)) {
            candidates.clear();
        }
    }

    /**
     * 获取命令运行时类对应的候选注册项，带缓存。
     *
     * @param commandClass 命令运行时类
     * @return 候选列表，已按注册顺序排序且不可修改
     */
    private List<CommandRegistration> candidatesFor(Class<?> commandClass) {
        List<CommandRegistration> cached = candidates.get(commandClass);
        if (cached != null) {
            return cached;
        }
        List<CommandRegistration> collected = new ArrayList<>();
        for (CommandRegistration registration : handlers.values()) {
            if (registration.getCommandType().isAssignableFrom(commandClass)) {
                collected.add(registration);
            }
        }
        collected.sort(Comparator.comparingLong(CommandRegistration::getSequence));
        List<CommandRegistration> immutable = Collections.unmodifiableList(collected);
        List<CommandRegistration> raced = candidates.putIfAbsent(commandClass, immutable);
        return raced == null ? immutable : raced;
    }

    /**
     * 判断注册项路由键是否匹配命令路由键。
     *
     * @param registration 注册项
     * @param routeKey     命令路由键，可为 {@code null}
     * @return 匹配返回 {@code true}
     */
    private static boolean matchesRoute(CommandRegistration registration, String routeKey) {
        return registration.getRouteKey() == null || registration.getRouteKey().equals(routeKey);
    }
}
