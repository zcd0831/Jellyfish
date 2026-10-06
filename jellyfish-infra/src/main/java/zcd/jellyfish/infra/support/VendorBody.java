package zcd.jellyfish.infra.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code models.json} 里 {@code vendorBody} 的清洗与合并：让用户能把厂商私有字段原样带进请求体。
 * <p>
 * <b>段名为什么以 {@code vendor} 开头</b>：读配置的人要能一眼看出「这一段里的键由厂商定义、内核不解释」。
 * 与之相对的是 {@code sampling} 段——那里的键是<b>内核定义的封闭字段集</b>，带校验、带「未表态」语义
 * （虽然各家认其中哪几项不同）。两条边界并存于同一份配置里，名字上先分开，否则一旦「配了不生效」，
 * 没人知道该怀疑哪一边。
 * <p>
 * <b>为什么需要它</b>：{@code reasoning_effort}、{@code thinking}、{@code service_tier} 这类字段
 * 各家叫法、语义、单位都不一样，内核认了就等于替厂商背书一个不成立的抽象。用户比内核更清楚自己的
 * 端点认哪些字段，因此这里只做「搬运」：<b>内核不解释任何键的含义，也不校验它是否被厂商认识</b>。
 * 代价是明说过的——字段名打错就是静默无效（厂商多半忽略不认识的字段），这是直通的固有属性。
 * <p>
 * <b>但有几个键必须挡住，否则直通会拆掉内核自己的不变量</b>（见 {@link #RESERVED_KEYS}）：
 * <ul>
 *     <li><b>结构性键</b>（{@code messages} / {@code tools} / {@code model} / {@code stream} …）——
 *     它们决定请求的形状。用户覆盖 {@code messages} 等于换一份对话发出去，而缓存前缀的不变量
 *     （见 {@code PromptAssembler}）建立在「内核独占这两段内容」之上；</li>
 *     <li><b>采样类键</b>（{@code temperature} / {@code top_p} / {@code max_tokens} …）——
 *     它们已经有正式入口（{@code models.json} 的 {@code sampling} 段）。同一参数两个入口，
 *     迟早会出现「两处都配了、行为却不是任何一处」的局面；其中 {@code max_tokens} 还担着
 *     缓存保活的「最省输出」换算，被覆盖就不是省钱而是发一份完整请求。</li>
 * </ul>
 * 保留键在<b>任意嵌套深度</b>都挡：Gemini 的生成参数在 {@code generationConfig} 里
 * （{@code generationConfig.temperature}），只看顶层等于给同一个参数留了后门。
 * <p>
 * <b>清洗在解析配置时做、合并在组装请求时做</b>：配置期丢弃非法键并记 WARN（每次 {@code /reload}
 * 最多每处一条），请求期只做纯函数式的深合并——这条路径每次调用都走，不能做 I/O 或刷日志。
 * <p>
 * 不可变：{@link #sanitize} 返回的结构（含嵌套 Map / List）都是只读副本，读取方改不动它，
 * 也改不动原始入参。
 *
 * @author zcd
 */
public final class VendorBody {

    /**
     * 透传的最大嵌套深度。
     * <p>
     * 它不是安全边界（解析已经由 Jackson 完成），而是一道防呆：真正需要嵌套的厂商字段
     * （Gemini 的 {@code generationConfig.thinkingConfig}）只有两三层，写超了多半是配错了层级。
     * 超过这个深度的子树整体丢弃并记 WARN，而不是截断——截断会发出去一份「看起来配了」的半截参数。
     */
    public static final int MAX_DEPTH = 8;

    /**
     * 保留键：内核自己生成这些键，用户写了也只丢不覆盖。
     * <p>
     * 大小写敏感（厂商字段名就是大小写敏感的）：{@code topP} 是 Gemini 的、{@code top_p} 是 OpenAI 与
     * Claude 的，{@code stopSequences} 是 Gemini 的、{@code stop_sequences} 是 Claude 的，
     * 少写一个就等于给同一个参数留了后门（它会被深合并整体替换掉内核生成的那份，且不报错）。
     * 采样类的每一项都按这个方式列全：{@code seed} 两家同名，{@code frequency_penalty} 与
     * {@code frequencyPenalty} 是 OpenAI 与 Gemini 的两种拼法。
     * <p>
     * {@code n} / {@code candidateCount} 也在这里，但理由不同：它们不是「已有正式入口」，
     * 而是<b>内核只解析第一个候选</b>（{@code choices.get(0)} / {@code candidates.get(0)}），
     * 配上去只会让计费翻倍而多出来的候选被丢掉。
     */
    private static final Set<String> RESERVED_KEYS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            // 结构性：请求形状与缓存前缀
            "model", "messages", "contents", "system", "systemInstruction", "tools", "toolConfig",
            "tool_choice", "stream", "stream_options", "prompt_cache_key", "prompt_cache_retention",
            // 多候选：内核只解析第一个候选（choices.get(0) / candidates.get(0)），配了就是白花钱
            "n", "candidateCount",
            // 采样类：已有 sampling 段这个正式入口
            "temperature", "top_p", "topP", "top_k", "topK", "seed",
            "frequency_penalty", "frequencyPenalty", "presence_penalty", "presencePenalty",
            "stop", "stop_sequences", "stopSequences", "max_tokens", "maxOutputTokens",
            // 输出上限的另一种拼法：由 model 的 maxTokensField 决定用哪个，不能两处各配一个
            "max_completion_tokens",
            // Anthropic 的缓存断点标记：内核按 cacheBreakpoints 与 cacheRetention 显式标注，
            // 用户从顶层再标一份会与块级标记的 TTL 冲突（Anthropic 对不一致的 TTL 直接 400）。
            // 要调 TTL 用 cacheRetention，要调断点数用 cacheBreakpoints
            "cache_control")));

    /** 被丢弃的值在内部流转时用的哨兵，避免用 {@code null} 表达「丢弃」（{@code null} 是合法值）。 */
    private static final Object DROPPED = new Object();

    /** 日志只用于配置期的一次性告警；请求期不刷日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(VendorBody.class);

    /** 工具类，不实例化。 */
    private VendorBody() {
    }

    /**
     * 清洗一段 {@code vendorBody}：丢弃保留键、超深子树与非法值，并把结果深拷贝为只读结构。
     *
     * @param raw   原始配置值，可为 {@code null}
     * @param owner 归属描述（如 {@code provider[openai]}），仅用于告警文本
     * @return 只读副本；入参为空时返回空映射而非 {@code null}
     */
    public static Map<String, Object> sanitize(Map<String, Object> raw, String owner) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        copyEntries(result, raw, 1, owner, "", true);
        return Collections.unmodifiableMap(result);
    }

    /**
     * 判断一个键是否为保留键。
     *
     * @param key 待判断的键
     * @return 是保留键时返回 {@code true}
     */
    public static boolean isReserved(String key) {
        return key != null && RESERVED_KEYS.contains(key);
    }

    /**
     * 深合并两份 {@code vendorBody}：对象递归合并，数组与标量整体由 {@code override} 替换。
     * <p>
     * <b>为什么必须是深合并</b>：Gemini 的生成参数全在 {@code generationConfig} 这个容器里。
     * 浅合并下，用户写 {@code {"generationConfig":{"thinkingConfig":{…}}}} 会把内核生成的
     * {@code generationConfig.maxOutputTokens} 整块挤掉——一份「看起来配上了」的请求，实际上丢了参数。
     * <p>
     * <b>数组为什么整体替换而不是按位合并</b>：按位合并没有能自洽的规则（长度不同时谁赢、
     * 元素是对象时按什么键对齐），而唯一不会让人意外的语义就是「你写的替换我写的」。
     *
     * @param base     基线（provider 级），可为 {@code null}
     * @param override 覆盖（model 级），可为 {@code null}
     * @return 合并结果，只读；两份都空时返回空映射
     */
    public static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> override) {
        if (base == null || base.isEmpty()) {
            return override == null ? Collections.<String, Object>emptyMap() : override;
        }
        if (override == null || override.isEmpty()) {
            return base;
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>(base);
        for (Map.Entry<String, Object> entry : override.entrySet()) {
            Object existing = result.get(entry.getKey());
            Object value = entry.getValue();
            if (existing instanceof Map && value instanceof Map) {
                result.put(entry.getKey(), merge(castMap(existing), castMap(value)));
            } else {
                result.put(entry.getKey(), value);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 把 {@code extra} 深合并进已经构造好的请求体，<b>就地生效</b>。
     * <p>
     * 就地修改而不是返回新对象：调用点是把结果直接序列化发出去的，返回新对象时忘一次赋值
     * 就是一个「配了没生效且不报错」的 bug，而就地修改没有可遗忘的步骤。
     * <p>
     * 这里会再挡一次保留键，属于防御性冗余：正常路径上它们已在解析配置时被清掉，
     * 而 {@code LlmRequest} 也可能被插件或测试直接构造，不能假设入库的东西一定干净。
     * <b>这一次的丢弃只记 DEBUG</b>：请求期是热路径，配置问题已经在解析期告警过，
     * 每轮对话再刷一条 WARN 只会把真正有用的那条淹掉。
     *
     * @param body  已构造好的请求体，不可为 {@code null}
     * @param extra 直通字段，可为 {@code null}
     * @return 本次实际下发的顶层键名列表，只用于日志（<b>不含值</b>：值可能带凭据）
     */
    public static List<String> applyTo(Map<String, Object> body, Map<String, Object> extra) {
        List<String> applied = new ArrayList<String>();
        if (body == null || extra == null || extra.isEmpty()) {
            return applied;
        }
        Map<String, Object> filtered = new LinkedHashMap<String, Object>();
        copyEntries(filtered, extra, 1, "request", "", false);
        for (Map.Entry<String, Object> entry : filtered.entrySet()) {
            Object existing = body.get(entry.getKey());
            Object value = entry.getValue();
            if (existing instanceof Map && value instanceof Map) {
                body.put(entry.getKey(), merge(castMap(existing), castMap(value)));
            } else {
                body.put(entry.getKey(), value);
            }
            applied.add(entry.getKey());
        }
        if (!applied.isEmpty() && LOG.isDebugEnabled()) {
            // 只打键名：直通的值可能是任意内容（含凭据），进日志就是泄密
            LOG.debug("出站请求体已应用 vendorBody 直通字段: keys={}", applied);
        }
        return applied;
    }

    /**
     * 把一段映射的键值按清洗规则拷入目标映射。
     *
     * @param target  目标映射
     * @param source  来源映射
     * @param depth   当前深度，顶层为 1
     * @param owner   归属描述，仅用于告警文本
     * @param path    当前路径前缀（如 {@code generationConfig}），仅用于告警文本
     * @param verbose 丢弃时是否记 WARN（配置期 {@code true}、请求期 {@code false}）
     */
    private static void copyEntries(Map<String, Object> target, Map<String, Object> source,
                                    int depth, String owner, String path, boolean verbose) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            String keyPath = path.isEmpty() ? String.valueOf(key) : path + "." + key;
            if (key == null || key.trim().isEmpty()) {
                logDrop(verbose, "vendorBody 的空白键已丢弃: owner={}", owner);
                continue;
            }
            if (RESERVED_KEYS.contains(key)) {
                logDrop(verbose, "vendorBody 的保留键已丢弃（该参数请用内核字段配置）: owner={} key={}", owner, keyPath);
                continue;
            }
            if (depth > MAX_DEPTH) {
                logDrop(verbose, "vendorBody 嵌套超过 {} 层，整棵子树已丢弃: owner={} key={}",
                        MAX_DEPTH, owner, keyPath);
                continue;
            }
            Object value = copyValue(entry.getValue(), depth, owner, keyPath, verbose);
            if (value != DROPPED) {
                target.put(key, value);
            }
        }
    }

    /**
     * 深拷贝一个值，沿途继续套用同一套清洗规则。
     *
     * @param value   原始值
     * @param depth   当前深度
     * @param owner   归属描述，仅用于告警文本
     * @param path    当前路径，仅用于告警文本
     * @param verbose 丢弃时是否记 WARN
     * @return 只读副本；该值非法时返回 {@link #DROPPED}
     */
    private static Object copyValue(Object value, int depth, String owner, String path, boolean verbose) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map) {
            Map<String, Object> nested = new LinkedHashMap<String, Object>();
            copyEntries(nested, castMap(value), depth + 1, owner, path, verbose);
            return Collections.unmodifiableMap(nested);
        }
        if (value instanceof Collection) {
            List<Object> items = new ArrayList<Object>();
            int index = 0;
            for (Object item : (Collection<?>) value) {
                Object copied = copyValue(item, depth + 1, owner, path + "[" + index + "]", verbose);
                if (copied != DROPPED) {
                    items.add(copied);
                }
                index++;
            }
            return Collections.unmodifiableList(items);
        }
        logDrop(verbose, "vendorBody 的值类型无法透传，已丢弃: owner={} key={}", owner, path);
        return DROPPED;
    }

    /**
     * 记一条「丢弃了什么」的日志：配置期是 WARN，请求期降为 DEBUG。
     *
     * @param verbose 是否按 WARN 记
     * @param fmt     日志模板
     * @param args    模板参数
     */
    private static void logDrop(boolean verbose, String fmt, Object... args) {
        if (verbose) {
            LOG.warn(fmt, args);
        } else {
            LOG.debug(fmt, args);
        }
    }

    /**
     * 把「值确定是映射」的断言收在一处，避免在合并逻辑里散落强制转换。
     *
     * @param value 待转换的值
     * @return 映射视图
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
