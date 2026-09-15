package zcd.jellyfish.plugin.todo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.event.notification.UiInvalidatedEvent;
import zcd.jellyfish.api.extension.CommandDescriptor;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.extension.PromptContributionRequest;
import zcd.jellyfish.api.extension.SessionDeleteRequest;
import zcd.jellyfish.api.extension.StatusLineContributionRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.plugin.JellyfishPlugin;
import zcd.jellyfish.api.plugin.PluginContext;

import java.nio.file.Path;

/**
 * 官方待办插件：把会话待办做成插件能力，内核不再持有该领域。
 * <p>
 * <b>五个面各占一个扩展点，且都不需要新扩展点</b>：
 * <ul>
 *     <li>{@code todo_write} 工具 → {@link ToolCallRequest}，模型写待办的唯一入口；</li>
 *     <li>{@code /todo} 命令 → {@link CommandRequest}，给人看的只读清单；</li>
 *     <li>待办注入 system prompt → {@link PromptContributionRequest}，让模型每轮都看得见自己的计划；</li>
 *     <li>状态栏进度 → {@link StatusLineContributionRequest}，不敲命令也能看到还剩几件事；</li>
 *     <li>待办面板 → {@link PanelContributionRequest}，在侧栏常驻显示完整清单。</li>
 * </ul>
 * <p>
 * <b>第六个扩展点是生命周期清理</b>：{@link SessionDeleteRequest}，内核删除会话时把本会话的待办文件
 * 一并删掉。待办按 {@code sessionId} 归属，会话没了它就没有意义；不处理的话每删一个会话就会多一个
 * 孤儿待办文件——而删除会话这件事本身就是为了不再堆积。
 * <p>
 * <b>插件为什么能拥有这份状态</b>：待办只需 {@code sessionId} 作为归属，而命令与工具请求都带它；
 * 存储由插件自己的文件负责，内核既不用新增会话字段，也不必为它保留任何调用点。反过来，待办也因此
 * 不可能「做不成插件」——它不属于需要碰会话内部结构的类型。
 * <p>
 * 配置见 {@link PluginConfig}：{@code todoDir}，默认 {@code ~/jellyfish/todos}。
 *
 * @author zcd
 */
public final class TodoPlugin implements JellyfishPlugin {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(TodoPlugin.class);

    @Override
    public void start(PluginContext context) {
        PluginConfig config = PluginConfig.from(context.configuration());
        Path directory = config.todoDirectory();
        // 先装配仓库再注册：处理器一旦注册就可能被调用，依赖必须已经就绪
        TodoStore store = new TodoStore(directory);
        context.handle(CommandRequest.class, "todo",
                new CommandDescriptor("查看当前会话待办", null, null), new TodoCommand(store));
        context.contribute(PromptContributionRequest.class, new TodoPromptContribution(store));
        context.contribute(StatusLineContributionRequest.class, new TodoStatusLine(store));
        context.contribute(PanelContributionRequest.class, new TodoPanel(store));
        // 会话删除时清掉本会话的待办文件（lambda 只为把 store 带进处理器，逻辑全在 TodoStore）
        context.contribute(SessionDeleteRequest.class, request -> {
            store.delete(request.getSessionId());
            return null;
        });
        registerWriteTool(context, store);
        LOG.info("待办插件已启动: dir={}", directory);
    }

    /**
     * 注册 {@code todo_write} 工具，并在写成功后广播一次 UI 失效。
     * <p>
     * <b>为什么包一层而不是让工具自己发事件</b>：工具只该关心「把待办存好」，
     * 「状态栏要刷新」是插件的展示职责；把两者分开，{@link TodoWriteTool} 就可以在没有任何
     * 插件上下文的情况下单测。
     * <p>
     * <b>为什么写失败也发</b>：不必分辨——失效只是「下一帧重问一次」，写失败时状态没变，
     * 重问一次所得与之前完全相同，而少发一次的代价是「某条分支忘了发」导致内容陈旧。
     *
     * @param context 插件上下文
     * @param store   待办仓库
     */
    private static void registerWriteTool(final PluginContext context, TodoStore store) {
        final ExtensionHandler<ToolCallRequest, ToolCallResult> writeTool = new TodoWriteTool(store);
        context.handle(ToolCallRequest.class, TodoWriteTool.NAME, TodoWriteTool.descriptor(),
                request -> {
                    ToolCallResult result = writeTool.handle(request);
                    context.emit(new UiInvalidatedEvent());
                    return result;
                });
    }
}
