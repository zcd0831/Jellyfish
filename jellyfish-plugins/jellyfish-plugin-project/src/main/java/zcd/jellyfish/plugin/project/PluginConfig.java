package zcd.jellyfish.plugin.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;

import java.util.Collections;
import java.util.Map;

/**
 * 本插件的配置解析：把 {@code jellyfish.json} 里的
 * {@code plugins.configurations.jellyfish-project} 段解析成值对象。
 * <p>
 * <b>只有一项配置</b>：{@code maxInlineBytes}——约定文件多大以内可以把**原文**放进 system prompt。
 * <p>
 * <b>为什么默认值是 32768</b>：与 Codex 的 {@code project_doc_max_bytes} 同口径，是一个好解释的数字；
 * 更本质的理由是 system prompt 每一轮都要随请求付一次 token，这个值就是「每轮最多为项目约定多付多少」。
 * <p>
 * <b>为什么 {@code 0} 是合法值</b>：它表示「从不内联」，等价于本插件早期版本的纯路径指引行为。
 * 对在意「仓库内容不得进入 system prompt」的用户，这是一个明确的逃生门，而不是要把值调成 1。
 * <p>
 * <b>值非法直接抛</b>：类型不对、为负或超出上限属配置错误，启动期就该让人看见，
 * 而不是压到运行时变成「内联怎么不生效」。上限存在的意义是兜住「往每轮 system prompt 里塞一兆文本」
 * 这种明显是笔误的配置。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class PluginConfig {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(PluginConfig.class);

    /** 内联上限配置键。 */
    static final String KEY_MAX_INLINE_BYTES = "maxInlineBytes";

    /** 缺省内联上限：32 KiB，与 Codex 的 project_doc_max_bytes 同口径。 */
    static final int DEFAULT_MAX_INLINE_BYTES = 32 * 1024;

    /** 允许配置的内联上限最大值：1 MiB。 */
    static final int MAX_INLINE_BYTES_LIMIT = 1024 * 1024;

    /** 内联上限，单位字节；{@code 0} 表示从不内联。 */
    private final int maxInlineBytes;

    /**
     * 构造配置。
     *
     * @param maxInlineBytes 内联上限（字节），不可为负
     */
    private PluginConfig(int maxInlineBytes) {
        this.maxInlineBytes = maxInlineBytes;
    }

    /**
     * 从插件配置段解析配置。
     *
     * @param configuration 插件配置段，可为 {@code null}
     * @return 配置值对象，保证非 {@code null}
     * @throws JellyfishException 配置值的类型或取值非法时抛出
     */
    static PluginConfig from(Map<String, Object> configuration) {
        Map<String, Object> values = configuration == null ? Collections.<String, Object>emptyMap()
                : configuration;
        int maxInlineBytes = nonNegativeInt(values.get(KEY_MAX_INLINE_BYTES), KEY_MAX_INLINE_BYTES);
        LOG.debug("项目约定插件配置: maxInlineBytes={}", maxInlineBytes);
        return new PluginConfig(maxInlineBytes);
    }

    /**
     * 获取内联上限。
     *
     * @return 上限字节数；{@code 0} 表示从不内联
     */
    int maxInlineBytes() {
        return maxInlineBytes;
    }

    /**
     * 解析一个非负整数配置项：缺省（{@code null}）返回缺省值，其余必须是 {@code [0, 1 MiB]} 内的整数。
     *
     * @param raw 配置原值，可为 {@code null}
     * @param key 配置键，用于错误信息
     * @return 解析结果，未配置时为缺省值
     * @throws JellyfishException 值不是非负整数或超出上限时抛出
     */
    private static int nonNegativeInt(Object raw, String key) {
        if (raw == null) {
            return DEFAULT_MAX_INLINE_BYTES;
        }
        long value;
        if (raw instanceof Number) {
            value = ((Number) raw).longValue();
        } else if (raw instanceof String) {
            try {
                value = Long.parseLong(((String) raw).trim());
            } catch (NumberFormatException e) {
                throw new JellyfishException(key + " 必须是整数，实际为 " + raw, e);
            }
        } else {
            throw new JellyfishException(key + " 必须是整数，实际为 " + raw);
        }
        if (value < 0 || value > MAX_INLINE_BYTES_LIMIT) {
            throw new JellyfishException(
                    key + " 必须在 0 到 " + MAX_INLINE_BYTES_LIMIT + " 之间，实际为 " + value);
        }
        return (int) value;
    }
}
