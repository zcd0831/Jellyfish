package zcd.jellyfish.core.prompt;

import zcd.jellyfish.infra.llm.LlmMessage;

/**
 * 工具调用配对约束：出站消息序列必须满足两条厂商共同强制的规则。
 * <p>
 * <b>规则</b>：
 * <ol>
 *     <li>每条 {@code tool} 消息都必须有一条<b>前置</b>的 {@code assistant(toolCalls)}，且
 *     其 {@code tool_call_id} 能对上；</li>
 *     <li>每条 {@code assistant(toolCalls)} 都必须紧跟齐它的全部 {@code tool} 结果。</li>
 * </ol>
 * 违反任一条，厂商会直接以 400 拒掉<b>整次请求</b>（DeepSeek 的实测文案是
 * {@code Messages with role 'tool' must be a response to a preceding message with 'tool_calls'} 与
 * {@code An assistant message with 'tool_calls' must be followed by tool messages responding to each
 * 'tool_call_id'}）。
 * <p>
 * <b>为什么需要这个类</b>：只有「调用模型 → 执行工具 → 再调用模型」这一条正常路径天然满足它。
 * 另外两条路径都会破坏它，而它们各自都不觉得自己在做危险的事：
 * <ul>
 *     <li><b>压缩边界按条数计算</b>（{@code messages.size() - keepRecent}），而 {@code tool} 消息在
 *     ReAct 会话里占相当比例，因此边界很容易正好落在 {@code assistant(toolCalls)} 与它的工具结果之间，
 *     切出来的序列就以孤儿 {@code tool} 消息开头；</li>
 *     <li><b>回合被取消</b>时 {@code assistant(toolCalls)} 已经落盘、而工具结果一条都还没落，
 *     于是会话以一个悬空的工具调用结尾。</li>
 * </ul>
 * 两条路径的产物都会让该会话在边界下一次推进之前<b>每一次请求都失败</b>——而边界只在
 * 「下一次压缩」时才推进，因此现场表现是「改配置、换模型都救不回来，过一阵自己又好了」，
 * 排查成本极高。因此把约束集中写在这里，由调用点各自执行。
 * <p>
 * <b>本类只回答「是不是」，不改消息</b>：怎么处置（向后对齐、向前跳过、丢弃）由调用点决定，
 * 因为「多留一组」与「少发一条」对上下文与缓存的影响并不相同。
 * <p>
 * 无状态工具类，不允许实例化。
 *
 * @author zcd
 */
public final class ToolPairing {

    /**
     * 工具类，禁止实例化。
     */
    private ToolPairing() {
    }

    /**
     * 判断一条消息是否是工具结果。
     *
     * @param message 消息，可为 {@code null}
     * @return 是 {@code tool} 角色返回 {@code true}；{@code null} 返回 {@code false}
     */
    public static boolean isToolResult(LlmMessage message) {
        return message != null && LlmMessage.ROLE_TOOL.equals(message.getRole());
    }

    /**
     * 判断一条消息是否是「必须紧跟齐工具结果」的 assistant 工具调用。
     * <p>
     * 谓词只回答它是不是这种消息，不回答它的结果是否齐全——「齐全」要看它后面跟了什么，
     * 那是调用点在一段序列上才能判断的事。
     *
     * @param message 消息，可为 {@code null}
     * @return 是带工具调用的 {@code assistant} 消息返回 {@code true}；{@code null} 返回 {@code false}
     */
    public static boolean requiresToolResults(LlmMessage message) {
        return message != null && LlmMessage.ROLE_ASSISTANT.equals(message.getRole())
                && message.hasToolCalls();
    }
}
