package zcd.jellyfish.script;

import zcd.jellyfish.api.extension.CommandOptionRequest;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ExtensionRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.script.codec.CommandCodec;
import zcd.jellyfish.script.codec.CommandOptionsCodec;
import zcd.jellyfish.script.codec.ExtensionCodec;
import zcd.jellyfish.script.codec.ExtensionCodecs;
import zcd.jellyfish.script.codec.ToolCodec;

import java.util.ArrayList;
import java.util.List;

/**
 * 脚本注册器：把一份清单变成内核注册表里的一组转发处理器。
 * <p>
 * <b>它是「脚本与 Java 插件同构」这句话真正落地的地方</b>：内核看到的是一批普通的
 * {@code ExtensionHandler}，与 Java 插件注册的毫无差别；区别只在处理器背后不是本地代码，
 * 而是一次进程往返（由 {@link ScriptCaller} 承载）。
 * <p>
 * <b>注册逐条隔离，一条失败不影响其余</b>：内核的「同键唯一」语义会在重名时抛异常
 * （比如两个插件的工具名撞了）。若让这个异常冒泡，后果是桥接插件 {@code start()} 失败、
 * 整门语言的所有脚本一起失效——用一个人的错误惩罚所有人。因此这里逐条捕获，
 * 把失败收敛成 {@link ScriptIssue}，其余条目照常注册。
 * <p>
 * <b>注册必须发生在 {@code start()} 窗口内</b>：本类的调用点是插件 {@code start(PluginContext)}。
 * 这也是清单必须存在的原因——若注册依赖脚本运行时上报，就必然在 {@code start()} 里阻塞等待进程，
 * 而进程一旦起不来，插件就只能整个失败。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ScriptRegistrar {

    /** 扩展点编解码器注册表。 */
    private final ExtensionCodecs codecs;

    /** 脚本调用入口。 */
    private final ScriptCaller caller;

    /**
     * 构造注册器。
     *
     * @param codecs 扩展点编解码器注册表，不可为 {@code null}
     * @param caller 脚本调用入口，不可为 {@code null}
     */
    public ScriptRegistrar(ExtensionCodecs codecs, ScriptCaller caller) {
        this.codecs = codecs;
        this.caller = caller;
    }

    /**
     * 按清单注册一个脚本的全部贡献。
     * <p>
     * <b>传入的上下文决定注册的 owner</b>：调用方若要给每个脚本独立的归因，
     * 传入该脚本自己的子上下文即可；传根上下文则全部注册落在插件标识下。
     * 本类不关心这件事，只负责把上下文透传给注册表——这让「注册了什么」与「登记在谁名下」
     * 两件事保持独立。
     *
     * @param context 注册落点上下文（决定 owner），不可为 {@code null}
     * @param plugin  目标脚本，不可为 {@code null}
     * @return 注册结果，保证非 {@code null}
     */
    public ScriptRegistration register(PluginContext context, ScriptPlugin plugin) {
        ScriptManifest manifest = plugin.manifest();
        List<Subscription> subscriptions = new ArrayList<Subscription>();
        List<ScriptIssue> issues = new ArrayList<ScriptIssue>();
        String source = plugin.id();
        for (ScriptManifest.Tool tool : manifest.tools()) {
            registerTool(context, plugin, tool, subscriptions, issues);
        }
        for (ScriptManifest.Command command : manifest.commands()) {
            registerCommand(context, plugin, command, subscriptions, issues);
            if (command.hasOptions()) {
                registerCommandOptions(context, plugin, command.name(), subscriptions, issues);
            }
        }
        for (String command : manifest.commandOptions()) {
            registerCommandOptions(context, plugin, command, subscriptions, issues);
        }
        for (String typeName : manifest.contributions()) {
            registerContribution(context, plugin, source, typeName, subscriptions, issues);
        }
        return new ScriptRegistration(source, subscriptions, issues);
    }

    /**
     * 注册一个工具。
     *
     * @param context       注册落点上下文
     * @param plugin        目标脚本
     * @param tool          工具声明
     * @param subscriptions 注册句柄输出
     * @param issues        问题输出
     */
    private void registerTool(PluginContext context, ScriptPlugin plugin, ScriptManifest.Tool tool,
                              List<Subscription> subscriptions, List<ScriptIssue> issues) {
        ToolCodec codec = new ToolCodec();
        ToolDescriptor descriptor = new ToolDescriptor(tool.name(), tool.description(), tool.parameters(),
                tool.required(), tool.readOnly());
        try {
            subscriptions.add(context.handle(ToolCallRequest.class, tool.name(), descriptor,
                    handlerFor(codec, plugin), RegisterOptions.DEFAULT));
        } catch (RuntimeException e) {
            issues.add(new ScriptIssue(plugin.id(), "工具注册失败 " + tool.name() + ": " + messageOf(e)));
        }
    }

    /**
     * 注册一条命令。
     *
     * @param context       注册落点上下文
     * @param plugin        目标脚本
     * @param command       命令声明
     * @param subscriptions 注册句柄输出
     * @param issues        问题输出
     */
    private void registerCommand(PluginContext context, ScriptPlugin plugin, ScriptManifest.Command command,
                                 List<Subscription> subscriptions, List<ScriptIssue> issues) {
        CommandCodec codec = new CommandCodec();
        try {
            subscriptions.add(context.handle(CommandRequest.class, command.name(), command.descriptor(),
                    handlerFor(codec, plugin), RegisterOptions.DEFAULT));
        } catch (RuntimeException e) {
            issues.add(new ScriptIssue(plugin.id(), "命令注册失败 " + command.name() + ": " + messageOf(e)));
        }
    }

    /**
     * 注册一条命令的候选查询。
     *
     * @param context       注册落点上下文
     * @param plugin        目标脚本
     * @param commandName   命令名
     * @param subscriptions 注册句柄输出
     * @param issues        问题输出
     */
    private void registerCommandOptions(PluginContext context, ScriptPlugin plugin, String commandName,
                                        List<Subscription> subscriptions, List<ScriptIssue> issues) {
        CommandOptionsCodec codec = new CommandOptionsCodec();
        try {
            subscriptions.add(context.handle(CommandOptionRequest.class, commandName, null,
                    handlerFor(codec, plugin), RegisterOptions.DEFAULT));
        } catch (RuntimeException e) {
            issues.add(new ScriptIssue(plugin.id(),
                    "命令候选查询注册失败 " + commandName + ": " + messageOf(e)));
        }
    }

    /**
     * 注册一个类型级贡献。
     * <p>
     * 这里是全类唯一需要 unchecked 转换的地方，转的是 {@code codec} 本身：{@code codec} 内部保证
     * 「请求类型 ↔ 结果类型」严格配对（编码时按声明类型取值、解码时按声明类型构造），
     * 而注册表只会用它注册时的那个请求类型调用它，因此运行期不存在错配的可能。
     * 转换收在这里的好处是：{@link ExtensionCodecs} 可以按「类型名」查表，
     * 而不必为 11 个扩展点各写一条强类型分支。
     *
     * @param context       注册落点上下文
     * @param plugin        目标脚本
     * @param source        问题归因用的来源
     * @param typeName      扩展点类型名
     * @param subscriptions 注册句柄输出
     * @param issues        问题输出
     */
    @SuppressWarnings("unchecked")
    private void registerContribution(PluginContext context, ScriptPlugin plugin, String source, String typeName,
                                      List<Subscription> subscriptions, List<ScriptIssue> issues) {
        ExtensionCodec<?, ?> found = codecs.byName(typeName);
        if (found == null) {
            // 清单解析已经校验过类型名，走到这里说明 codecs 被换成了另一份注册表
            issues.add(new ScriptIssue(source, "未知扩展点类型: " + typeName));
            return;
        }
        ExtensionCodec<ExtensionRequest<Object>, Object> codec =
                (ExtensionCodec<ExtensionRequest<Object>, Object>) found;
        try {
            subscriptions.add(context.contribute(codec.requestType(), null, handlerFor(codec, plugin),
                    RegisterOptions.DEFAULT));
        } catch (RuntimeException e) {
            issues.add(new ScriptIssue(source, "贡献注册失败 " + typeName + ": " + messageOf(e)));
        }
    }

    /**
     * 为扩展点造转发处理器。
     *
     * @param codec  编解码器
     * @param plugin 目标脚本
     * @param <C>    请求类型
     * @param <R>    结果类型
     * @return 处理器
     */
    private <C extends ExtensionRequest<R>, R> ExtensionHandler<C, R> handlerFor(
            ExtensionCodec<C, R> codec, ScriptPlugin plugin) {
        return codec.handlerTo((typeName, request) -> caller.call(plugin, typeName, request));
    }

    /**
     * 取异常的可读消息。
     *
     * @param throwable 异常
     * @return 消息文本，保证非 {@code null}
     */
    private static String messageOf(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty() ? throwable.getClass().getName() : message;
    }
}
