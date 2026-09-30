package zcd.jellyfish.api.extension;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 老化策略：插件对「本次请求里较早的工具结果该怎么处理」的回答——<b>两个数量参数 + stub 文案</b>，
 * 没有「删哪条消息」的决定权。
 * <p>
 * <b>为什么 stub 文案值得开放</b>：老化把旧工具结果换成一行短占位，而那一行是<b>模型唯一还能看到的
 * 线索</b>——它决定模型是「知道这里曾经有个结果、也知道去哪找回来」，还是「彻底失忆」。
 * 那一行该怎么写是<b>领域知识</b>：写工具插件的作者比内核更清楚自己的输出长什么样、
 * 以及「这里以前有什么」的最小可信提示该写什么。内核给的是通用措辞，插件能给的是贴切的措辞。
 * <p>
 * <b>能改的只有文案与两个阈值</b>：<b>边界怎么算</b>（老化到哪一条为止、一个压缩周期内推进几次）
 * 全部留在内核。这是刻意的边界——那套算法是「前缀不变量」的守卫，而前缀不变量是<b>全局</b>性质，
 * 改坏它会让整条缓存在每一轮都失效（详见 {@code docs/design/llm-cache.md} 的 R2）。
 * <p>
 * <b>模板占位符</b>（与 {@link CompactionStrategy} 的花括号约定一致）：
 * <table border="1">
 *     <caption>可用占位符</caption>
 *     <tr><th>占位符</th><th>含义</th></tr>
 *     <tr><td>{@value #TOOL_PLACEHOLDER}</td><td>工具名</td></tr>
 *     <tr><td>{@value #CHARS_PLACEHOLDER}</td><td>原始输出的字符数</td></tr>
 *     <tr><td>{@value #LINES_PLACEHOLDER}</td><td>原始输出的行数</td></tr>
 *     <tr><td>{@value #FIRST_LINE_PLACEHOLDER}</td><td>预览首行，<b>已含前导分隔符</b>；无首行时为空串</td></tr>
 *     <tr><td>{@value #RECOVERY_PLACEHOLDER}</td><td>「怎么找回内容」整段后缀，<b>已含前导分隔符</b>；
 *     它内含「落盘失败 / 落盘不完整 / 正常」三种情况的分支，因此必须以整段形式给出</td></tr>
 * </table>
 * 未出现的占位符只是不被替换，不算错误。不认识的字面量原样保留——**刻意不做「未知占位符报错」**：
 * 那会让一个插件因为多写了一个花括号就整功能不可用，而这行文案本来只是锦上添花。
 * <p>
 * <b>缺省模板就是内核一直在用的那一行</b>，因此不注册处理器时行为完全不变。
 *
 * @author zcd
 */
public final class AgingStrategy {

    /** 工具名占位符。 */
    public static final String TOOL_PLACEHOLDER = "{tool}";

    /** 原始字符数占位符。 */
    public static final String CHARS_PLACEHOLDER = "{chars}";

    /** 原始行数占位符。 */
    public static final String LINES_PLACEHOLDER = "{lines}";

    /** 预览首行占位符（已含前导分隔符，无首行时为空串）。 */
    public static final String FIRST_LINE_PLACEHOLDER = "{firstLine}";

    /** 「怎么找回内容」后缀占位符（已含前导分隔符）。 */
    public static final String RECOVERY_PLACEHOLDER = "{recovery}";

    /** 保留最近多少条原文不老化；{@code null} 表示不表态。 */
    private final Integer keepRecentMessages;

    /**
     * 老化水位百分比（上下文用量达到它才开始老化）；{@code null} 表示不表态。
     * <p>
     * {@code 0} 是<b>有意义的取值</b>：它表示「回到按距尾部条数的旧口径」，而不是「关闭老化」。
     * 关闭老化是 {@code react.toolOutput.keepRecentMessages = 0}。
     */
    private final Integer agingPercent;

    /** stub 模板；{@code null} 表示用内核缺省。 */
    private final String stubText;

    /** 按工具名覆盖的 stub 模板，键为工具名；{@code null} 或空表示不覆盖。 */
    private final Map<String, String> stubTextsByTool;

    /**
     * 构造老化策略。
     *
     * @param keepRecentMessages 保留最近多少条原文，可为 {@code null}
     * @param agingPercent       老化水位百分比，可为 {@code null}
     * @param stubText           stub 模板，可为 {@code null}
     * @param stubTextsByTool    按工具名覆盖的 stub 模板，可为 {@code null}
     */
    public AgingStrategy(Integer keepRecentMessages, Integer agingPercent, String stubText,
                         Map<String, String> stubTextsByTool) {
        this.keepRecentMessages = keepRecentMessages;
        this.agingPercent = agingPercent;
        this.stubText = stubText;
        this.stubTextsByTool = stubTextsByTool == null || stubTextsByTool.isEmpty()
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(stubTextsByTool));
    }

    /**
     * 构造一个「什么都不表态」的策略，行为与没有本扩展点时一致。
     *
     * @return 空策略
     */
    public static AgingStrategy none() {
        return new AgingStrategy(null, null, null, null);
    }

    /**
     * 获取保留条数。
     *
     * @return 条数，可能为 {@code null}
     */
    public Integer getKeepRecentMessages() {
        return keepRecentMessages;
    }

    /**
     * 获取老化水位百分比。
     *
     * @return 百分比，可能为 {@code null}
     */
    public Integer getAgingPercent() {
        return agingPercent;
    }

    /**
     * 获取 stub 模板。
     *
     * @return 模板，可能为 {@code null}
     */
    public String getStubText() {
        return stubText;
    }

    /**
     * 获取按工具名覆盖的 stub 模板。
     *
     * @return 不可变的映射，可能为空但不会为 {@code null}
     */
    public Map<String, String> getStubTextsByTool() {
        return stubTextsByTool;
    }

    /**
     * 判断四个字段是否都没表态。
     *
     * @return 都没表态返回 {@code true}
     */
    public boolean isEmpty() {
        return keepRecentMessages == null && agingPercent == null && stubText == null
                && stubTextsByTool.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AgingStrategy)) {
            return false;
        }
        AgingStrategy that = (AgingStrategy) other;
        return Objects.equals(keepRecentMessages, that.keepRecentMessages)
                && Objects.equals(agingPercent, that.agingPercent)
                && Objects.equals(stubText, that.stubText)
                && stubTextsByTool.equals(that.stubTextsByTool);
    }

    @Override
    public int hashCode() {
        return Objects.hash(keepRecentMessages, agingPercent, stubText, stubTextsByTool);
    }

    @Override
    public String toString() {
        return "AgingStrategy{keepRecentMessages=" + keepRecentMessages + ", agingPercent=" + agingPercent
                + ", stubText=" + stubText + ", stubTextsByTool=" + stubTextsByTool + '}';
    }
}
