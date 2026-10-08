package zcd.jellyfish.server.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.server.http.ApiException;
import zcd.jellyfish.server.http.LogText;
import zcd.jellyfish.server.http.Responses;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 命令端点的闸门：拒绝「在按 id 寻址的服务化外壳里没有意义、或会改进程级状态」的命令。
 * <p>
 * <b>为什么服务模式也需要这道闸门</b>：{@code POST /sessions/{id}/commands} 原本可以把任何命令
 * 跑起来——包括 {@code /new}（改进程级「当前会话」指针）、{@code /resume}（切当前会话）、
 * {@code /reload}（重载进程配置：模型 / agent / 插件）、{@code /session}（列出全部会话）、
 * {@code /delete}（删的是<b>参数里那个</b>会话，参数不受本层的按 id 寻址保护）。这些命令在 TUI/CLI
 * 上都有意义，在服务化外壳里没有：客户端本来就按 id 寻址，不需要「当前会话」这个概念。
 * <p>
 * <b>名单是常量，不开放配置</b>：它是「这七条命令的语义与本外壳的寻址方式不兼容」，不是随场景调整的策略。
 * 多租户下需要可配的闸门在 Spring starter 的 {@code CommandGate} 里（那份名单可按租户调）。
 * <p>
 * <b>只做「拒绝」不做事后过滤</b>：拒绝的理由直接回给调用方，它知道该换哪条路（建会话用
 * {@code POST /sessions}，删会话用 {@code DELETE /sessions/{id}}，改配置就重启或走运维入口）。
 * <p>
 * <b>命令名从原始文本里取</b>：刻意不引用 {@code CommandManager} 的解析结果——把「要判定的东西」
 * 交给「被判定的一方」去解析（它认识别名、也认补全），判定就不再是独立的。取不到名字时放行，
 * 让内核自己去报「未知命令」。
 *
 * @author zcd
 */
final class ServerCommandGate {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ServerCommandGate.class);

    /** 服务模式下不可通过 HTTP 执行的命令：语义与本外壳的寻址方式不兼容，或改进程级状态。 */
    private static final Set<String> DENIED = Collections.unmodifiableSet(new LinkedHashSet<String>(
            Arrays.asList("new", "resume", "reload", "session", "sessions", "delete", "rm")));

    /**
     * 工具类，禁止实例化。
     */
    private ServerCommandGate() {
    }

    /**
     * 检查一条命令能否通过 HTTP 执行。
     *
     * @param commandLine 命令原文，形如 {@code /reload}
     * @throws ApiException 命中拒绝名单时抛出 403
     */
    static void check(String commandLine) {
        String name = nameOf(commandLine);
        if (name.isEmpty() || !DENIED.contains(name)) {
            return;
        }
        // 命令名来自请求原文，因此只进日志、不进响应文案
        LOG.info("服务模式拒绝了命令: /{}", LogText.singleLine(name));
        throw new ApiException(Responses.FORBIDDEN, "COMMAND_NOT_ALLOWED",
                "该命令不能在服务模式下执行：它会操作进程级状态或按参数操作别的会话，"
                        + "而本外壳按会话 id 寻址（建会话用 POST /sessions，删会话用 DELETE /sessions/{id}）");
    }

    /**
     * 从命令原文里取出命令名（去掉前缀与参数，转小写）。
     *
     * @param commandLine 命令原文，可为 {@code null}
     * @return 命令名；不是命令时返回空串
     */
    static String nameOf(String commandLine) {
        if (commandLine == null) {
            return "";
        }
        String trimmed = commandLine.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '/') {
            return "";
        }
        String withoutPrefix = trimmed.substring(1);
        for (int i = 0; i < withoutPrefix.length(); i++) {
            if (Character.isWhitespace(withoutPrefix.charAt(i))) {
                return withoutPrefix.substring(0, i).toLowerCase(Locale.ROOT);
            }
        }
        return withoutPrefix.toLowerCase(Locale.ROOT);
    }
}
