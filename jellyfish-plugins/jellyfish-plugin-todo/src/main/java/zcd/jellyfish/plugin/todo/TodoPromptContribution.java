package zcd.jellyfish.plugin.todo;

import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PromptContribution;
import zcd.jellyfish.api.extension.PromptContributionRequest;

/**
 * 提示词贡献：把当前会话的待办注入 system prompt。
 * <p>
 * <b>为什么注入而不是追加成消息</b>：待办是「本轮的上下文」，不是对话历史。塞进消息列表会被后续
 * 每轮重新 append 回会话，导致重复累积、回放与 token 统计失真。
 * <p>
 * <b>没有待办时返回空贡献</b>：本处理器每轮都会被问到，而多数轮次并没有待办要注入——返回
 * {@link PromptContribution#empty()} 正是这个扩展点为「无事可说」留的表达。
 * <p>
 * 无状态，可安全跨线程传递。
 *
 * @author zcd
 */
final class TodoPromptContribution implements ExtensionHandler<PromptContributionRequest, PromptContribution> {

    /** 待办仓库。 */
    private final TodoStore store;

    /**
     * 构造贡献处理器。
     *
     * @param store 待办仓库，不可为 {@code null}
     */
    TodoPromptContribution(TodoStore store) {
        this.store = store;
    }

    @Override
    public PromptContribution handle(PromptContributionRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return PromptContribution.empty();
        }
        return PromptContribution.of(TodoText.promptBlock(store.itemsOf(sessionId)));
    }
}
