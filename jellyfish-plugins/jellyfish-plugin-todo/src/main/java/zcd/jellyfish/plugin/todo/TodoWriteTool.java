package zcd.jellyfish.plugin.todo;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code todo_write} 工具：模型写待办的唯一入口。
 * <p>
 * <b>整表覆盖而不是增量</b>：模型手上没有稳定的编号，让它 {@code add 1} / {@code done 2} 就得先把编号读回来，
 * 多一轮往返且容易记错；传一整份列表则「增、删、改、重排」共用同一种表达，也不会出现两份互相打架的状态。
 * 编号只在渲染给人看时按位置生成。
 * <p>
 * <b>参数是不可信输入</b>：模型可能传错类型、漏字段、写错枚举值。这里一律当场抛
 * {@link JellyfishException}——ReAct 会把工具异常转成 tool 结果回灌，模型因此能看着错误信息自己改，
 * 而不是让一份坏数据静默落盘。
 * <p>
 * 无状态，可安全跨线程传递。
 *
 * @author zcd
 */
final class TodoWriteTool implements ExtensionHandler<ToolCallRequest, ToolCallResult> {

    /** 工具名，同时是路由键。 */
    static final String NAME = "todo_write";

    /** 状态取值：未完成。 */
    private static final String STATUS_PENDING = "pending";

    /** 状态取值：已完成。 */
    private static final String STATUS_COMPLETED = "completed";

    /** 待办仓库。 */
    private final TodoStore store;

    /**
     * 构造工具。
     *
     * @param store 待办仓库，不可为 {@code null}
     */
    TodoWriteTool(TodoStore store) {
        this.store = store;
    }

    /**
     * 构造工具名片：名称、用途与参数 Schema。
     *
     * @return 工具描述符
     */
    static ToolDescriptor descriptor() {
        Map<String, Object> itemProperties = new LinkedHashMap<String, Object>();
        itemProperties.put("content", property("string", "待办内容，一句话说清要做什么"));
        Map<String, Object> status = property("string", "pending 表示未完成，completed 表示已完成");
        status.put("enum", Arrays.asList(STATUS_PENDING, STATUS_COMPLETED));
        itemProperties.put("status", status);

        Map<String, Object> itemSchema = new LinkedHashMap<String, Object>();
        itemSchema.put("type", "object");
        itemSchema.put("properties", itemProperties);
        itemSchema.put("required", Arrays.asList("content", "status"));

        Map<String, Object> todosSchema = property("array",
                "完整的新待办列表。整表覆盖：上一次列过而这次没列出的项视为删除，"
                        + "不要只传变化的部分；用空数组清空整个列表。");
        todosSchema.put("items", itemSchema);

        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("todos", todosSchema);

        return new ToolDescriptor(NAME,
                "创建或更新本会话的待办清单。开始一项需要多步的工作时，先把计划写成待办，"
                        + "并在推进过程中把完成的项标记为 completed，让用户能看到进度。"
                        + "内容要简短、可执行；同一时间只应有一项是当前正在做的。",
                properties, Collections.singletonList("todos"));
    }

    @Override
    public ToolCallResult handle(ToolCallRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new JellyfishException("todo_write 需要会话上下文，当前没有会话");
        }
        List<TodoItem> stored = store.replace(sessionId, parse(request.getArguments().get("todos")));
        return new ToolCallResult(NAME, TodoText.confirmation(stored));
    }

    /**
     * 解析并校验模型传来的待办列表。
     *
     * @param raw {@code todos} 参数原值，可为 {@code null}
     * @return 待办列表，保证非 {@code null}
     * @throws JellyfishException 参数缺失或类型非法时抛出
     */
    private static List<TodoItem> parse(Object raw) {
        if (!(raw instanceof Collection)) {
            throw new JellyfishException("todos 必须是数组，实际为 " + describe(raw));
        }
        List<TodoItem> items = new ArrayList<TodoItem>(((Collection<?>) raw).size());
        for (Object element : (Collection<?>) raw) {
            if (!(element instanceof Map)) {
                throw new JellyfishException("todos 的每一项都必须是对象，实际为 " + describe(element));
            }
            Map<?, ?> item = (Map<?, ?>) element;
            Object content = item.get("content");
            if (!(content instanceof String) || ((String) content).trim().isEmpty()) {
                throw new JellyfishException("todos 每一项的 content 必须是非空字符串，实际为 " + describe(content));
            }
            items.add(new TodoItem((String) content, isDone(item.get("status"))));
        }
        return items;
    }

    /**
     * 解析状态取值。
     *
     * @param status {@code status} 参数原值，可为 {@code null}（按未完成处理）
     * @return 已完成返回 {@code true}
     * @throws JellyfishException 取值不在允许集合内时抛出
     */
    private static boolean isDone(Object status) {
        if (status == null) {
            // 少写状态时按「未完成」处理：这是更容易改对的一侧，且不会把计划项当成已完成
            return false;
        }
        if (status instanceof String) {
            String text = ((String) status).trim();
            if (STATUS_PENDING.equals(text)) {
                return false;
            }
            if (STATUS_COMPLETED.equals(text)) {
                return true;
            }
        }
        throw new JellyfishException("todos 的 status 只能是 " + STATUS_PENDING + " 或 "
                + STATUS_COMPLETED + "，实际为 " + describe(status));
    }

    /**
     * 构造一个 JSON Schema 属性定义。
     *
     * @param type        类型名
     * @param description 说明
     * @return 可继续补充字段的属性映射
     */
    private static Map<String, Object> property(String type, String description) {
        Map<String, Object> property = new LinkedHashMap<String, Object>();
        property.put("type", type);
        property.put("description", description);
        return property;
    }

    /**
     * 生成便于排错的参数描述。
     *
     * @param value 参数值，可为 {@code null}
     * @return 描述文本
     */
    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
