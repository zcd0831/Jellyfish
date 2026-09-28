package zcd.jellyfish.plugin.shell;

import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.PermissionVerdict;

import java.util.Objects;

/**
 * 把命令分类结果翻译成权限裁定。
 * <p>
 * <b>为什么在处理器里再判一次工具名</b>：{@link PermissionCheckRequest} 是<b>类型级</b>扩展点，
 * 注册之后每一次工具调用的权限检查都会进来——包括 {@code read_file}、{@code todo_write} 等等。
 * 不给非本工具的命令表态不是风格问题：插件返回的裁定会参与「取最严」的合并，
 * 而给别的工具下结论等于越过自己的边界去管别人。
 * <p>
 * <b>为什么可以返回 ASK 而不是只有两态</b>：权限裁定在前一版是「插件只能拒绝」的两态，
 * 命令行恰好是那个说明两态不够用的例子——只读查询不该打扰人，写类命令要人看一眼，
 * 而两态下用户只剩「全放行」与「每次都点批准」两个选择，后者最终会让人把授权整个关掉。
 * <p>
 * <b>为什么处理器必须只读且快</b>：它跑在权限判定的同步路径上（每一轮、每次工具调用），
 * 且拿不到会话正文。这里只做字符串前缀匹配，不碰文件系统、不启动进程。
 * <p>
 * 无状态，可安全复用。
 *
 * @author zcd
 */
final class ShellPermissionContribution implements ExtensionHandler<PermissionCheckRequest, PermissionVerdict> {

    /** 命令策略。 */
    private final CommandPolicy policy;

    /**
     * 构造处理器。
     *
     * @param policy 命令策略，不可为 {@code null}
     */
    ShellPermissionContribution(CommandPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    @Override
    public PermissionVerdict handle(PermissionCheckRequest request) {
        if (!ShellTool.TOOL_NAME.equals(request.getToolName())) {
            return PermissionVerdict.abstain();
        }
        Object command = request.getArguments().get("command");
        if (!(command instanceof String) || ((String) command).trim().isEmpty()) {
            // 拿不到命令原文时不能假设它无害：空声明等于放行，宁可让人看一眼
            return PermissionVerdict.ask("无法读取命令原文，需要确认");
        }
        return policy.verdict(((String) command).trim());
    }
}
