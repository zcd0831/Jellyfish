package zcd.jellyfish.plugin.todo;

import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.StatusLineContribution;
import zcd.jellyfish.api.extension.StatusLineContributionRequest;

/**
 * 状态栏贡献处理器：把当前会话的待办进度压成一小段文本。
 * <p>
 * <b>为什么是「进度」而不是清单</b>：状态栏只有一行，且内核字段已经占了大部分宽度；清单表达不了，
 * 进度却能让人一眼看出「还有几件事没做完」。要看清单请用 {@code /todo} 命令。
 * <p>
 * <b>为什么没有待办时返回空贡献</b>：空的 {@code 待办 0/0} 只是噪音——它既不提供信息，又占掉状态栏
 * 本该留给别的插件的列。
 * <p>
 * <b>与「处理器不得做 I/O」契约的关系</b>：本仓库的 UI 贡献契约要求处理器纯只读、不做 I/O，
 * 而 {@link TodoStore} 在某个会话<b>首次</b>被访问时会懒加载一次它的文件——不读就不知道有待办，
 * 状态栏就会在「本来有待办的会话」上一直空着。这是一次<b>有意识的偏离</b>：每会话至多读一次，
 * 之后全部命中内存缓存；且只读当前会话那一个文件，不做目录扫描。
 *
 * @author zcd
 */
final class TodoStatusLine implements ExtensionHandler<StatusLineContributionRequest, StatusLineContribution> {

    /** 待办仓库。 */
    private final TodoStore store;

    /**
     * 构造处理器。
     *
     * @param store 待办仓库，不可为 {@code null}
     */
    TodoStatusLine(TodoStore store) {
        this.store = store;
    }

    @Override
    public StatusLineContribution handle(StatusLineContributionRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null) {
            return StatusLineContribution.empty();
        }
        String text = TodoText.statusLine(store.itemsOf(sessionId));
        return text == null ? StatusLineContribution.empty() : StatusLineContribution.of(text);
    }
}
