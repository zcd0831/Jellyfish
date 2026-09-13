package zcd.jellyfish.infra.event.callback;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.callback.Callback;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扩展点定义注册表：承载形状预设，回答「这个回调类型属于哪个扩展点、用哪组参数」。
 * <p>
 * 定义在启动期由 {@link #register(Class)} 显式登记并解析，登记时即校验标识唯一性；
 * 未登记的回调类型在注册处理器与解析时都会 fail-fast，避免「新增回调类型忘了声明形状」这类隐性漂移。
 *
 * @author zcd
 */
public final class ExtensionPointRegistry {

    /** 回调类型 → 定义。 */
    private final Map<Class<? extends Callback<?>>, ExtensionPointDefinition> byType = new ConcurrentHashMap<>();

    /** 扩展点标识 → 定义。 */
    private final Map<String, ExtensionPointDefinition> byId = new ConcurrentHashMap<>();

    /**
     * 构造仅含内核内置扩展点定义的注册表。
     *
     * @return 已登记内置定义的注册表
     */
    public static ExtensionPointRegistry withBuiltIns() {
        ExtensionPointRegistry registry = new ExtensionPointRegistry();
        registry.register(zcd.jellyfish.api.event.callback.ToolCallRequest.class);
        registry.register(zcd.jellyfish.api.event.callback.PermissionCheckRequest.class);
        registry.register(zcd.jellyfish.api.event.callback.PluginRequest.class);
        return registry;
    }

    /**
     * 登记一个回调类型并解析其定义。
     *
     * @param callbackType 回调类型，不可为 {@code null}
     * @return 解析后的定义
     * @throws JellyfishException 类型未标注 {@link zcd.jellyfish.api.event.callback.ExtensionPoint}，
     *                            或标识与已登记的其他类型冲突时抛出
     */
    public ExtensionPointDefinition register(Class<? extends Callback<?>> callbackType) {
        ExtensionPointDefinition registered = byType.get(callbackType);
        if (registered != null) {
            return registered;
        }
        ExtensionPointDefinition definition = ExtensionPointDefinition.resolve(callbackType);
        ExtensionPointDefinition existing = byId.putIfAbsent(definition.getId(), definition);
        if (existing != null && !existing.getCallbackType().equals(callbackType)) {
            throw new JellyfishException("duplicate extension point id: " + definition.getId()
                    + " used by " + existing.getCallbackType().getName() + " and " + callbackType.getName());
        }
        byType.put(callbackType, definition);
        return definition;
    }

    /**
     * 获取回调类型的定义。
     *
     * @param callbackType 回调类型，不可为 {@code null}
     * @return 定义
     * @throws JellyfishException 该类型未登记时抛出
     */
    public ExtensionPointDefinition definitionOf(Class<? extends Callback<?>> callbackType) {
        ExtensionPointDefinition definition = byType.get(callbackType);
        if (definition == null) {
            throw new JellyfishException("no extension point definition for callback type: " + callbackType.getName());
        }
        return definition;
    }

    /**
     * 判断回调类型是否已登记定义。
     *
     * @param callbackType 回调类型
     * @return 已登记返回 {@code true}
     */
    public boolean isRegistered(Class<? extends Callback<?>> callbackType) {
        return byType.containsKey(callbackType);
    }

    /**
     * 渲染诊断视图。
     *
     * @return 多行文本，无定义时返回空串
     */
    public String render() {
        if (byType.isEmpty()) {
            return "";
        }
        List<ExtensionPointDefinition> definitions = new ArrayList<>(byType.values());
        definitions.sort(Comparator.comparing(ExtensionPointDefinition::getId));
        StringBuilder builder = new StringBuilder("extensionPoints:\n");
        for (ExtensionPointDefinition definition : definitions) {
            builder.append("  ").append(definition.getId())
                    .append("  ").append(definition.getShape())
                    .append("  unique=").append(definition.isUnique())
                    .append(" ordered=").append(definition.isOrdered())
                    .append(" empty=").append(definition.getEmptyPolicy())
                    .append(" arity=").append(definition.getResultArity())
                    .append(" execution=").append(definition.getExecution())
                    .append(" failure=").append(definition.getFailurePolicy())
                    .append("  <- ").append(definition.getCallbackType().getSimpleName())
                    .append('\n');
        }
        return builder.toString();
    }

    /**
     * 清空注册表，用于总线关闭时释放引用。
     */
    public void clear() {
        byType.clear();
        byId.clear();
    }
}
