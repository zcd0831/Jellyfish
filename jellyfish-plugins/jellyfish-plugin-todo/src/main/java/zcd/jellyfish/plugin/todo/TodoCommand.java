package zcd.jellyfish.plugin.todo;

import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.ExtensionHandler;

/**
 * {@code /todo} 命令：只读地列出当前会话的待办。
 * <p>
 * <b>刻意只读</b>：待办是模型的计划草稿，写入路径只有 {@code todo_write} 一条；给人一条 {@code /todo add}
 * 只会让「谁写的这条待办」变得无法归因，也让同一个列表出现两套写入语义。但只读命令要保留——目前没有
 * 第二个地方能让人看见模型正在计划什么。
 * <p>
 * 无状态，可安全跨线程传递。
 *
 * @author zcd
 */
final class TodoCommand implements ExtensionHandler<CommandRequest, CommandResult> {

    /** 待办仓库。 */
    private final TodoStore store;

    /**
     * 构造命令处理器。
     *
     * @param store 待办仓库，不可为 {@code null}
     */
    TodoCommand(TodoStore store) {
        this.store = store;
    }

    @Override
    public CommandResult handle(CommandRequest request) {
        if (!request.getArguments().isEmpty()) {
            return CommandResult.error("用法：/todo（待办由模型通过 todo_write 维护，这里是只读的）");
        }
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return CommandResult.error("当前没有会话，可用 /new 新建。");
        }
        return CommandResult.ok(TodoText.list(store.itemsOf(sessionId)));
    }
}
