package zcd.jellyfish.plugin.shell;

import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.InputDirectiveRequest;
import zcd.jellyfish.api.extension.InputDirectiveResult;

import java.util.Collections;
import java.util.Map;

/**
 * 输入指令 {@code !}：把「行首一个感叹号 + 命令原文」翻译成一次 {@code shell} 工具调用。
 * <p>
 * <b>它只做翻译，不执行</b>：返回的是一份工具调用意图，真正的执行在 {@code ToolExecutor} 里——
 * 权限判定、人工审批、超时、进程树终止、输出截断与落盘全部与模型发起的工具调用一致。
 * 插件拿不到 {@code PermissionManager}，因此这里也不可能「顺手把命令跑了」。
 * <p>
 * <b>为什么不做任何命令解析</b>：命令原文原样交给 {@code /bin/sh -c}（与 {@code shell} 工具同口径），
 * 管道、重定向、复合命令都按 shell 语义工作。在这里做「安全解析」既不成立（可绕过），
 * 也会让 {@code !} 与模型调用的行为悄悄分叉。
 * <p>
 * <b>空命令不在这里拦</b>：{@code !} 后面什么都不写时返回一次空参数的工具调用，
 * 由 {@link ShellArguments#requireCommand()} 报出「参数 command 不能为空」并转成结果文本
 * ——错误文案只有一处，不必在两个地方各写一遍。
 * <p>
 * 无状态，可安全复用。
 *
 * @author zcd
 */
final class ShellInputDirective implements ExtensionHandler<InputDirectiveRequest, InputDirectiveResult> {

    /** 行首标记字符，同时是注册时的路由键。 */
    static final String MARKER = "!";

    @Override
    public InputDirectiveResult handle(InputDirectiveRequest request) {
        String input = request.getInput() == null ? "" : request.getInput().trim();
        if (!input.startsWith(MARKER)) {
            // 标记相同但语法不匹配（理论上不该发生，因为路由键就是首字符）
            return InputDirectiveResult.unclaimed();
        }
        String command = input.substring(MARKER.length()).trim();
        Map<String, Object> arguments = Collections.<String, Object>singletonMap("command", command);
        return InputDirectiveResult.toolCall(ShellTool.TOOL_NAME, arguments);
    }
}
