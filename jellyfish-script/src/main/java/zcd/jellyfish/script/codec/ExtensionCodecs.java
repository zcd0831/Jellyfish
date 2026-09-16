package zcd.jellyfish.script.codec;

import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 扩展点编解码器注册表：协议里的类型名 → {@link ExtensionCodec}。
 * <p>
 * <b>它是「有哪些扩展点」这件事的唯一真源</b>。这个事实同时被三处需要：
 * <ul>
 *     <li>注册：按清单声明的扩展点找到对应 codec；</li>
 *     <li>清单校验：{@code manifest.json} 的 {@code contributions} 只能取类型级扩展点的名字，
 *     取值合法性与「内核到底认识哪些扩展点」必须一致；</li>
 *     <li>调用路由：{@code invoke} 请求里的 {@code params.type}。</li>
 * </ul>
 * 三处各写一份名单，迟早会出现「清单里能声明、调用时找不到」或反过来的组合——
 * 那正是本方案最忌讳的静默失效。因此名单只在 {@link #defaults()} 里出现一次。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ExtensionCodecs {

    /** 内核默认支持的 11 个扩展点。 */
    public static final ExtensionCodecs DEFAULTS = new ExtensionCodecs(defaults());

    /** 类型名 → codec，保持声明顺序以便诊断输出稳定。 */
    private final Map<String, ExtensionCodec<?, ?>> byName;

    /**
     * 构造注册表。
     *
     * @param codecs 编解码器清单，不可为 {@code null}
     * @throws JellyfishException 存在空元素或类型名重复时抛出
     */
    public ExtensionCodecs(List<ExtensionCodec<?, ?>> codecs) {
        Map<String, ExtensionCodec<?, ?>> collected = new LinkedHashMap<String, ExtensionCodec<?, ?>>();
        for (ExtensionCodec<?, ?> codec : codecs) {
            if (codec == null) {
                throw new JellyfishException("扩展点编解码器不得为 null");
            }
            ExtensionCodec<?, ?> previous = collected.put(codec.typeName(), codec);
            if (previous != null) {
                throw new JellyfishException("扩展点类型名重复: " + codec.typeName());
            }
        }
        this.byName = Collections.unmodifiableMap(collected);
    }

    /**
     * 按类型名查找编解码器。
     *
     * @param typeName 类型名，可为 {@code null}
     * @return 编解码器；未知类型名时为 {@code null}
     */
    public ExtensionCodec<?, ?> byName(String typeName) {
        return typeName == null ? null : byName.get(typeName);
    }

    /**
     * 判断是否为已知的扩展点类型名。
     *
     * @param typeName 类型名，可为 {@code null}
     * @return 已知返回 {@code true}
     */
    public boolean isKnown(String typeName) {
        return byName(typeName) != null;
    }

    /**
     * 获取全部类型名。
     *
     * @return 不可变集合，顺序与声明顺序一致
     */
    public Set<String> names() {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(byName.keySet()));
    }

    /**
     * 获取全部「类型级」扩展点的类型名。
     * <p>
     * 供清单校验使用：{@code contributions} 只能声明这些名字；带路由键的扩展点（工具、命令、
     * 候选查询）各有自己的清单字段，混进来就说明作者写错了字段。
     *
     * @return 不可变集合
     */
    public Set<String> typeLevelNames() {
        Set<String> names = new LinkedHashSet<String>();
        for (Map.Entry<String, ExtensionCodec<?, ?>> entry : byName.entrySet()) {
            if (entry.getValue().isTypeLevel()) {
                names.add(entry.getKey());
            }
        }
        return Collections.unmodifiableSet(names);
    }

    /**
     * 判断类型名是否为「类型级」扩展点。
     *
     * @param typeName 类型名，可为 {@code null}
     * @return 是类型级扩展点返回 {@code true}；未知类型名返回 {@code false}
     */
    public boolean isTypeLevel(String typeName) {
        ExtensionCodec<?, ?> codec = byName(typeName);
        return codec != null && codec.isTypeLevel();
    }

    /**
     * 构造内核默认支持的扩展点清单。
     *
     * @return 编解码器清单
     */
    private static List<ExtensionCodec<?, ?>> defaults() {
        return new ArrayList<ExtensionCodec<?, ?>>(Arrays.<ExtensionCodec<?, ?>>asList(
                // 带路由键：一个名字一个实现
                new ToolCodec(),
                new CommandCodec(),
                new CommandOptionsCodec(),
                // 类型级：同一类型允许多个贡献
                new PromptCodec(),
                new StatusLineCodec(),
                new PanelCodec(),
                new PermissionCodec(),
                new SessionPersistCodec(),
                new SessionRestoreCodec(),
                new SessionDeleteCodec(),
                new CompactionCodec()));
    }
}
