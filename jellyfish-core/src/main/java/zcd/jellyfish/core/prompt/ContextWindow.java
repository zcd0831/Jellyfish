package zcd.jellyfish.core.prompt;

import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmToolCall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 上下文窗口：按 token 预算对「准备发给 LLM 的消息」做**机械裁剪**。
 * <p>
 * <b>只裁剪本次请求</b>：返回的是新的消息列表，{@code Session} 里存的历史一条不动——
 * 裁剪是可逆的展示优化，不是对会话内容的修改。
 * <p>
 * <b>成组保留</b>：{@code assistant(toolCalls)} 与其紧随的 {@code tool} 结果必须同生共死，
 * 否则请求本身非法（工具结果指向不存在的调用）。因此先按「工具结果挂到前一条消息」切成组，
 * 再从最新组向最旧组累加，装不下就整组丢弃。
 * <p>
 * <b>最新一组永远保留</b>：它是本轮新产生的上下文，丢了循环就没法继续；若它单独就超预算，
 * 则按预算等比截断组内消息的正文（保守按 1 字符 1 token 折算），工具调用参数不截断以免破坏 JSON。
 * <p>
 * 无状态工具类，不允许实例化。
 *
 * @author zcd
 */
public final class ContextWindow {

    /** 正文被截断时追加的标记。 */
    private static final String TRUNCATION_MARKER = "…（已截断）";

    private ContextWindow() {
    }

    /**
     * 按预算裁剪消息列表。
     *
     * @param messages            原始消息列表，可为 {@code null}
     * @param historyBudgetTokens 历史消息可用的 token 预算，{@code <= 0} 表示只保留最新一组
     * @return 裁剪结果，保证非 {@code null}
     */
    public static Result crop(List<LlmMessage> messages, int historyBudgetTokens) {
        if (messages == null || messages.isEmpty()) {
            return new Result(Collections.<LlmMessage>emptyList(), false);
        }
        List<List<LlmMessage>> groups = group(messages);
        if (historyBudgetTokens <= 0) {
            // 预算已经为系统提示词吃光：只能留最新一组，丢没丢旧组如实回报
            List<LlmMessage> newest = groups.get(groups.size() - 1);
            return new Result(immutableCopy(newest), groups.size() > 1);
        }

        List<LlmMessage> kept = new ArrayList<LlmMessage>();
        int used = 0;
        boolean truncated = false;
        for (int i = groups.size() - 1; i >= 0; i--) {
            List<LlmMessage> group = groups.get(i);
            int cost = TokenEstimator.estimateMessages(group);
            if (kept.isEmpty()) {
                if (cost > historyBudgetTokens) {
                    kept.addAll(truncateGroup(group, historyBudgetTokens));
                    used = TokenEstimator.estimateMessages(kept);
                } else {
                    kept.addAll(group);
                    used = cost;
                }
            } else if (used + cost <= historyBudgetTokens) {
                kept.addAll(0, group);
                used += cost;
            } else {
                truncated = true;
                break;
            }
        }
        return new Result(immutableCopy(kept), truncated);
    }

    /**
     * 把消息切成组：工具结果挂到前一条消息所在的组。
     *
     * @param messages 原始消息列表
     * @return 分组结果，按原顺序排列
     */
    private static List<List<LlmMessage>> group(List<LlmMessage> messages) {
        List<List<LlmMessage>> groups = new ArrayList<List<LlmMessage>>();
        for (LlmMessage message : messages) {
            boolean toolResult = LlmMessage.ROLE_TOOL.equals(message.getRole());
            if (toolResult && !groups.isEmpty()) {
                groups.get(groups.size() - 1).add(message);
            } else {
                List<LlmMessage> group = new ArrayList<LlmMessage>();
                group.add(message);
                groups.add(group);
            }
        }
        return groups;
    }

    /**
     * 把一组消息压缩到预算内：按组内条数平均分配预算，逐条截断正文。
     *
     * @param group        消息组
     * @param budgetTokens 该组可用的 token 预算
     * @return 截断后的消息组
     */
    private static List<LlmMessage> truncateGroup(List<LlmMessage> group, int budgetTokens) {
        int perMessage = Math.max(1, budgetTokens / group.size());
        List<LlmMessage> truncated = new ArrayList<LlmMessage>(group.size());
        for (LlmMessage message : group) {
            truncated.add(truncateMessage(message, perMessage));
        }
        return truncated;
    }

    /**
     * 截断单条消息的正文。
     * <p>
     * 保守按「1 字符 1 token」截取，保证估算值不会超预算；工具调用列表原样保留，
     * 因为截断 JSON 参数会让请求彻底非法。
     *
     * @param message   消息
     * @param maxTokens 允许的 token 上限
     * @return 截断后的消息；未超限时返回原实例
     */
    private static LlmMessage truncateMessage(LlmMessage message, int maxTokens) {
        if (TokenEstimator.estimateMessage(message) <= maxTokens) {
            return message;
        }
        String content = message.getContent();
        if (content != null && content.length() > maxTokens) {
            content = content.substring(0, maxTokens) + TRUNCATION_MARKER;
        }
        List<LlmToolCall> toolCalls = message.getToolCalls().isEmpty() ? null : message.getToolCalls();
        return new LlmMessage(message.getRole(), content, message.getToolCallId(), message.getName(), toolCalls);
    }

    /**
     * 生成不可修改副本。
     *
     * @param messages 原始列表
     * @return 不可修改副本
     */
    private static List<LlmMessage> immutableCopy(List<LlmMessage> messages) {
        return Collections.unmodifiableList(new ArrayList<LlmMessage>(messages));
    }

    /**
     * 裁剪结果：消息列表 + 是否发生了丢弃。
     * <p>
     * 不可变值对象，供调用点判断「本轮是否因超预算丢了历史」，以便提示用户或埋点。
     *
     * @author zcd
     */
    public static final class Result {

        /** 裁剪后的消息列表。 */
        private final List<LlmMessage> messages;

        /** 是否因超预算丢弃了历史消息。 */
        private final boolean truncated;

        /**
         * 构造裁剪结果。
         *
         * @param messages  裁剪后的消息列表
         * @param truncated 是否发生丢弃
         */
        Result(List<LlmMessage> messages, boolean truncated) {
            this.messages = messages;
            this.truncated = truncated;
        }

        /**
         * 获取裁剪后的消息列表。
         *
         * @return 不可修改列表，保证非 {@code null}
         */
        public List<LlmMessage> getMessages() {
            return messages;
        }

        /**
         * 判断是否发生了丢弃。
         *
         * @return 因超预算丢弃了历史消息返回 {@code true}
         */
        public boolean isTruncated() {
            return truncated;
        }
    }
}
