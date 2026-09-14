package zcd.jellyfish.core.prompt;

import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmToolCall;

import java.util.List;

/**
 * 近似 token 估算器：在缺少厂商 tokenizer 的前提下给出**保守**的上下文规模估计。
 * <p>
 * 为什么不引入真正的 tokenizer：Java 侧没有与各家厂商一致的切分实现，多引一个依赖也换不来准确；
 * 而裁剪只需要「宁可多留一点」的数量级判断，不需要精确。
 * <p>
 * 估算规则：CJK 字符（汉字 / 平假名 / 片假名 / 谚文）按 1 token、其余字符按 0.25 token 计，
 * 累加时以 1/4 token 为最小单位、最后向上取整；每条消息再叠加固定格式开销（角色、分隔符等）。
 * <p>
 * 无状态工具类，不允许实例化。
 *
 * @author zcd
 */
public final class TokenEstimator {

    /** 非 CJK 字符的换算：4 个字符约等于 1 token。 */
    private static final int QUARTER_UNITS_PER_TOKEN = 4;

    /** 每条消息的固定格式开销（角色、分隔、结束标记）。 */
    private static final int MESSAGE_OVERHEAD_TOKENS = 4;

    /** 每次工具调用的固定格式开销（id、type、function 包装）。 */
    private static final int TOOL_CALL_OVERHEAD_TOKENS = 2;

    private TokenEstimator() {
    }

    /**
     * 估算一段文本的 token 数。
     *
     * @param text 文本，可为 {@code null}
     * @return 估算值，空文本为 {@code 0}
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return toTokens(countUnits(text));
    }

    /**
     * 估算一组消息的 token 数。
     *
     * @param messages 消息列表，可为 {@code null}
     * @return 估算值，空列表为 {@code 0}
     */
    public static int estimateMessages(List<LlmMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (LlmMessage message : messages) {
            total += estimateMessage(message);
        }
        return total;
    }

    /**
     * 估算单条消息的 token 数，含角色、文本、工具名与工具调用参数。
     *
     * @param message 消息，可为 {@code null}
     * @return 估算值，{@code null} 为 {@code 0}
     */
    public static int estimateMessage(LlmMessage message) {
        if (message == null) {
            return 0;
        }
        int total = MESSAGE_OVERHEAD_TOKENS + estimate(message.getContent()) + estimate(message.getName());
        List<LlmToolCall> toolCalls = message.getToolCalls();
        for (LlmToolCall toolCall : toolCalls) {
            if (toolCall != null) {
                total += TOOL_CALL_OVERHEAD_TOKENS + estimate(toolCall.getName()) + estimate(toolCall.getArguments());
            }
        }
        return total;
    }

    /**
     * 把「1/4 token 单位数」向上取整成 token 数。
     *
     * @param units 单位数
     * @return token 数
     */
    private static int toTokens(long units) {
        return (int) ((units + QUARTER_UNITS_PER_TOKEN - 1) / QUARTER_UNITS_PER_TOKEN);
    }

    /**
     * 统计文本折算后的 1/4 token 单位数。
     *
     * @param text 文本
     * @return 单位数
     */
    private static long countUnits(String text) {
        long units = 0L;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            units += isWide(codePoint) ? QUARTER_UNITS_PER_TOKEN : 1L;
            index += Character.charCount(codePoint);
        }
        return units;
    }

    /**
     * 判断码点是否按 1 字符 1 token 计（CJK 与全角文字）。
     *
     * @param codePoint 码点
     * @return CJK 类字符返回 {@code true}
     */
    private static boolean isWide(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
