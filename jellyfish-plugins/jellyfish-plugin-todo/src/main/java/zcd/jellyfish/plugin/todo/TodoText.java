package zcd.jellyfish.plugin.todo;

import java.util.List;

/**
 * 待办的文本渲染：把同一份列表渲染成三种给人 / 给模型看的形态。
 * <p>
 * 集中在一处是为了让「模型看到的」与「人看到的」永远一致：两处各写一遍迟早会漂移
 * （例如一边显示 {@code [x]}、一边显示 {@code done}），而它们描述的本就是同一件事。
 *
 * @author zcd
 */
final class TodoText {

    /** 提示词里的待办块标题。 */
    static final String HEADER = "[待办]";

    /** 未完成标记。 */
    private static final String PENDING_MARK = "[ ] ";

    /** 已完成标记。 */
    private static final String DONE_MARK = "[x] ";

    /**
     * 工具类，禁止实例化。
     */
    private TodoText() {
    }

    /**
     * 渲染注入 system prompt 的待办块。
     * <p>
     * 与内核原先的形态一致（{@code [待办]} 标题 + 每行 {@code - [ ] 内容}）：模型看到的东西不该因为
     * 「待办从内核搬到插件」而变化。
     *
     * @param items 待办列表，不可为 {@code null}
     * @return 待办块文本；没有待办时返回 {@code null}
     */
    static String promptBlock(List<TodoItem> items) {
        if (items.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder(HEADER);
        for (TodoItem item : items) {
            text.append('\n').append("- ").append(mark(item)).append(item.content());
        }
        return text.toString();
    }

    /**
     * 渲染给用户看的带编号清单。
     *
     * @param items 待办列表，不可为 {@code null}
     * @return 清单文本；没有待办时是一句明确说明而不是空串
     */
    static String list(List<TodoItem> items) {
        if (items.isEmpty()) {
            return "当前没有待办。";
        }
        return "待办：" + numbered(items);
    }

    /**
     * 渲染工具执行后的确认文本：附带覆盖后的整份清单，让模型不必再读一次。
     *
     * @param items 覆盖后的待办列表，不可为 {@code null}
     * @return 确认文本
     */
    static String confirmation(List<TodoItem> items) {
        if (items.isEmpty()) {
            return "待办已清空。";
        }
        return "待办已更新：" + numbered(items);
    }

    /**
     * 渲染状态栏上的进度片段。
     *
     * @param items 待办列表，不可为 {@code null}
     * @return 形如 {@code 待办 2/5} 的片段；没有待办时返回 {@code null}（状态栏不显示）
     */
    static String statusLine(List<TodoItem> items) {
        if (items.isEmpty()) {
            return null;
        }
        int done = 0;
        for (TodoItem item : items) {
            if (item.done()) {
                done++;
            }
        }
        return "待办 " + done + "/" + items.size();
    }

    /**
     * 渲染带编号的清单主体（不含前缀）。
     *
     * @param items 非空待办列表
     * @return 每行 {@code "  [ ] 1. 内容"} 的多行文本
     */
    private static String numbered(List<TodoItem> items) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            TodoItem item = items.get(i);
            text.append('\n').append("  ").append(mark(item)).append(i + 1).append(". ").append(item.content());
        }
        return text.toString();
    }

    /**
     * 取单条待办的完成标记。
     *
     * @param item 待办项，不可为 {@code null}
     * @return 标记文本
     */
    private static String mark(TodoItem item) {
        return item.done() ? DONE_MARK : PENDING_MARK;
    }
}
