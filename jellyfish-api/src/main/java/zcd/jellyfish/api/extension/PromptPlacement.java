package zcd.jellyfish.api.extension;

/**
 * 提示词贡献块的稳定性分层：决定它排在 system prompt 的哪一段。
 * <p>
 * <b>为什么要有分层</b>：厂商的 prompt 缓存是<b>前缀匹配</b>——请求从第 0 个 token 起逐字节比对，
 * 改一个字节就让其后全部失效。而 system prompt 正是第 0 个 token 开始的那一段，因此「哪一块容易变」
 * 决定了「一次变化要付多少代价」。把跨会话恒定的内容排在最前、会话内会变的排到最后，
 * 能让最容易变的那一小段只作废它自己之后的部分，而不是让一次待办更新把整段 system prompt
 * 连同全部历史一起作废。
 * <p>
 * <b>分层只是第二道防线，第一道是「不要把易变状态放进 system prompt」</b>：能用
 * {@link TurnContextRequest} 的就别用 {@link PromptPlacement#VOLATILE}。后者无论排在哪，
 * 都会作废它之后的全部内容；而前者随用户消息落盘，是 append-only 的，只影响本轮新产生的 token。
 *
 * @author zcd
 */
public enum PromptPlacement {

    /**
     * 跨会话、跨轮次逐字节不变（例如「{@code @路径} 是文件引用」这条约定），排在最前。
     */
    STATIC,

    /**
     * 会话内不变（项目约定、skills 清单、可委派类型），排在中间。
     * <p>
     * 这是 {@link PromptContribution#of(String)} 的缺省分层：绝大多数贡献属于这一档，
     * 而「会话内不变」也正好是它们当前的实际行为。
     */
    SESSION,

    /**
     * 会话内可能变，排在 system prompt 最后；内核会对它的每次变化记一条缓存断裂告警。
     * <p>
     * <b>保留它只是为了「某些内容必须比对话历史拥有更高优先级」的少数场合。</b>
     * 每用一次，就要接受「它一变就作废整段请求」这个代价。
     */
    VOLATILE
}
