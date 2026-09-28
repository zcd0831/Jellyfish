package zcd.jellyfish.plugin.shell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.PermissionCheckRequest;
import zcd.jellyfish.api.extension.PermissionMode;
import zcd.jellyfish.api.extension.PermissionVerdict;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShellPermissionContribution} 的单元测试。
 * <p>
 * <b>最要紧的一条是不表态</b>：权限检查是<b>类型级</b>扩展点，每次工具调用的权限检查都会进来。
 * 若处理器不先判工具名就给出裁定，它就会给 {@code read_file}、{@code todo_write} 等
 * 所有工具下结论——那是越过自己的边界去管别人。
 *
 * @author zcd
 */
@DisplayName("ShellPermissionContribution")
class ShellPermissionContributionTest {

    /** 被测处理器（默认策略）。 */
    private final ShellPermissionContribution contribution =
            new ShellPermissionContribution(CommandPolicy.from(null, null, java.util.Collections.<String>emptyList()));

    @Test
    @DisplayName("别的工具一律不表态，避免越界影响其它工具的权限判定")
    void handle_should_abstain_forOtherTools() {
        PermissionVerdict verdict = contribution.handle(request("read_file", args("path", "/etc/passwd")));

        assertTrue(verdict.isAbstain(), verdict.toString());
    }

    @Test
    @DisplayName("拿不到命令原文时升级为审批，而不是当作无害放行")
    void handle_should_ask_whenCommandMissing() {
        assertTrue(contribution.handle(request(ShellTool.TOOL_NAME, args())).isAsk());
        assertTrue(contribution.handle(request(ShellTool.TOOL_NAME, args("command", "  "))).isAsk());
        assertTrue(contribution.handle(request(ShellTool.TOOL_NAME, args("command", 42))).isAsk());
    }

    @Test
    @DisplayName("只读命令不打扰人")
    void handle_should_abstain_forReadOnlyCommand() {
        assertTrue(contribution.handle(request(ShellTool.TOOL_NAME, args("command", "git status"))).isAbstain());
    }

    @Test
    @DisplayName("其余命令升级为人工审批")
    void handle_should_ask_forOrdinaryCommand() {
        PermissionVerdict verdict = contribution.handle(request(ShellTool.TOOL_NAME, args("command", "curl x")));

        assertTrue(verdict.isAsk(), verdict.toString());
        assertTrue(verdict.getReason().contains("curl"), verdict.getReason());
    }

    @Test
    @DisplayName("灾难形状直接拒绝")
    void handle_should_deny_disasterShape() {
        PermissionVerdict verdict = contribution.handle(request(ShellTool.TOOL_NAME, args("command", "rm -rf /")));

        assertTrue(verdict.isDenied(), verdict.toString());
        assertTrue(verdict.getReason().contains("拒绝形状"), verdict.getReason());
    }

    @Test
    @DisplayName("裁定里带上理由——审批浮层与审计都靠它说明为什么")
    void handle_should_carryReason() {
        PermissionVerdict ask = contribution.handle(request(ShellTool.TOOL_NAME, args("command", "npm test")));
        PermissionVerdict deny = contribution.handle(request(ShellTool.TOOL_NAME, args("command", "mkfs.ext4 /dev/sda1")));

        assertTrue(ask.getReason() != null && !ask.getReason().isEmpty());
        assertTrue(deny.getReason() != null && !deny.getReason().isEmpty());
    }

    @Test
    @DisplayName("白名单生效时未被列出的命令被拒绝")
    void handle_should_deny_whenAllowListConfigured() {
        ShellPermissionContribution restricted = new ShellPermissionContribution(
                CommandPolicy.from(null, null, java.util.Arrays.asList("git status")));

        assertTrue(restricted.handle(request(ShellTool.TOOL_NAME, args("command", "git status"))).isAbstain());
        assertEquals(PermissionVerdict.Outcome.DENY,
                restricted.handle(request(ShellTool.TOOL_NAME, args("command", "git push"))).getOutcome());
    }

    /**
     * 构造权限检查请求。
     *
     * @param toolName  工具名
     * @param arguments 工具参数
     * @return 请求
     */
    private static PermissionCheckRequest request(String toolName, Map<String, Object> arguments) {
        return new PermissionCheckRequest("default", toolName, arguments, PermissionMode.NORMAL, "s1");
    }

    /**
     * 构造参数映射。
     *
     * @param namesAndValues 参数名与值交替
     * @return 参数映射
     */
    private static Map<String, Object> args(Object... namesAndValues) {
        Map<String, Object> arguments = new HashMap<String, Object>();
        for (int index = 0; index < namesAndValues.length; index += 2) {
            arguments.put((String) namesAndValues[index], namesAndValues[index + 1]);
        }
        return arguments;
    }
}
