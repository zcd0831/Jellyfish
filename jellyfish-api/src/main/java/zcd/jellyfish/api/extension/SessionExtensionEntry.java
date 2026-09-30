package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话扩展条目：插件挂在自己命名空间下的会话级状态。
 * <p>
 * <b>解决什么问题</b>：插件此前想在会话里存状态，只能塞进工具结果的 {@code metadata}，
 * 因此<b>必须先把状态伪装成一次工具调用</b>——「这次会话已经检查过哪些文件」「当前工作流的这一步是第几次」
 * 这类东西本来与任何一次工具调用无关。本类型给它一个正当的位置。
 * <p>
 * <b>key 已经带上了 owner 前缀</b>：内核在写入时把插件给的 key 前缀成 {@code pluginId} 或
 * {@code pluginId::子标识}，因此插件之间互相看不见对方的条目，也无法写到别人的命名空间里——
 * 与注册的 owner 纪律完全一致。读回来时前缀还在，插件可以据此判断是哪一层写的。
 * <p>
 * <b>条目不进模型上下文</b>：与工具结果的 {@code metadata} 同口径，模型不需要它，界面与插件需要。
 * 它是会话的一部分（会随会话一起落盘），但不是 {@code LlmMessage} 的一部分。
 * <p>
 * <b>插件停止不删条目</b>：那是用户会话里的数据，不是插件的私有财产。插件卸载后条目留在会话文件里，
 * 重新装回来还能读到——否则会出现「插件升个级，历史里的状态也一起消失」。
 * <p>
 * <b>值类型是 {@code Map<String, Object>}</b>：与 {@link SessionMessageSnapshot#getMetadata()} 同口径，
 * 因此 {@code api} 不必依赖任何序列化库，具体编码由持久化插件决定。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class SessionExtensionEntry {

    /** 完整 key（含 owner 前缀）。 */
    private final String key;

    /** 值，保证非 {@code null}。 */
    private final Map<String, Object> value;

    /** 最后一次写入时间戳（epoch millis）。 */
    private final long updatedAt;

    /**
     * 构造扩展条目。
     * <p>
     * <b>为什么这里只有唯一一个构造器</b>：本类型随 {@link SessionSnapshot} 一起靠 Jackson 的
     * 「隐式属性构造器」反序列化（{@code -parameters} + {@code ParameterNamesModule}），
     * 而 Jackson 只在「恰好一个可见构造器」时才认它为隐式创建器；多出一个重载会让整个快照类型
     * <b>直接反序列化失败</b>，代价是整段会话读不回来。因此新增字段时不要加「兼容构造器」，
     * 兼容入口请改用静态工厂。
     *
     * @param key       完整 key，不可为空白
     * @param value     值，可为 {@code null}（等价空映射）
     * @param updatedAt 最后写入时间戳（epoch millis）
     * @throws JellyfishException key 为空白时抛出
     */
    public SessionExtensionEntry(String key, Map<String, Object> value, long updatedAt) {
        if (key == null || key.trim().isEmpty()) {
            throw new JellyfishException("session extension entry key must not be blank");
        }
        this.key = key;
        this.value = value == null || value.isEmpty()
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(value));
        this.updatedAt = updatedAt;
    }

    /**
     * 获取完整 key（含 owner 前缀）。
     *
     * @return key，保证非空白
     */
    public String getKey() {
        return key;
    }

    /**
     * 获取值。
     *
     * @return 不可变映射，未设置时为空映射而非 {@code null}
     */
    public Map<String, Object> getValue() {
        return value;
    }

    /**
     * 获取最后写入时间戳。
     *
     * @return 时间戳（epoch millis）
     */
    public long getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "SessionExtensionEntry{key=" + key + ", fields=" + value.size() + '}';
    }
}
