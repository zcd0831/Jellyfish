package zcd.jellyfish.plugin.todo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import zcd.jellyfish.api.JellyfishException;

/**
 * 一条待办：内容 + 是否完成。
 * <p>
 * <b>刻意只有两个字段</b>：待办是「本会话要给模型看的计划」，编号、创建时间这类元信息对模型没有意义，
 * 而对人可见的编号由渲染时按位置生成，不必落盘。字段越少，越不容易在插件与模型之间产生两套语义。
 * <p>
 * 用「全字段构造器 + 无 setter」的不可变形态：它同时是落盘 DTO，Jackson 用显式的
 * {@code @JsonCreator} / {@code @JsonProperty} 反序列化，因此不依赖编译期的 {@code -parameters}。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class TodoItem {

    /** 待办内容。 */
    private final String content;

    /** 是否已完成。 */
    private final boolean done;

    /**
     * 构造待办。
     *
     * @param content 待办内容，不可为空白
     * @param done    是否已完成
     * @throws JellyfishException 内容为空白时抛出
     */
    @JsonCreator
    TodoItem(@JsonProperty("content") String content, @JsonProperty("done") boolean done) {
        if (content == null || content.trim().isEmpty()) {
            throw new JellyfishException("todo content must not be blank");
        }
        this.content = content;
        this.done = done;
    }

    /**
     * 获取待办内容。
     *
     * @return 待办内容
     */
    @JsonProperty("content")
    String content() {
        return content;
    }

    /**
     * 判断是否已完成。
     *
     * @return 已完成返回 {@code true}
     */
    @JsonProperty("done")
    boolean done() {
        return done;
    }

    @Override
    public String toString() {
        return "TodoItem{done=" + done + ", content=" + content + '}';
    }
}
