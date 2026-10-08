package zcd.jellyfish.server.handler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.server.http.ApiException;
import zcd.jellyfish.server.http.Responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ServerCommandGate} 的判据：哪些命令不能从 HTTP 执行，以及「命令名怎么取」。
 *
 * @author zcd
 */
@DisplayName("服务模式的命令闸门")
class ServerCommandGateTest {

    @Test
    @DisplayName("进程级 / 按参数操作别的会话的命令一律拒绝（403）")
    void check_should_reject_whenCommandTouchesProcessOrOtherSessions() {
        for (String denied : new String[] {"/new", "/resume", "/reload", "/session", "/sessions",
                "/delete", "/rm"}) {
            ApiException error = assertThrows(ApiException.class, () -> ServerCommandGate.check(denied),
                    denied + " 应当被拒绝");
            assertEquals(Responses.FORBIDDEN, error.getStatus(), denied);
        }
    }

    @Test
    @DisplayName("按会话生效的命令照常放行")
    void check_should_allow_whenCommandIsSessionScoped() {
        ServerCommandGate.check("/compact preview");
        ServerCommandGate.check("/model deepseek-chat");
        ServerCommandGate.check("/plan on");
        ServerCommandGate.check("/help");
    }

    @Test
    @DisplayName("不是命令（不以 / 开头）时放行，交给内核去报「未知命令」")
    void check_should_allow_whenInputIsNotCommand() {
        ServerCommandGate.check("你好");
        ServerCommandGate.check("");
        ServerCommandGate.check(null);
    }

    @Test
    @DisplayName("命令名取「前缀之后的第一个词」并转小写：参数、大小写、空白都不影响判定")
    void nameOf_should_takeFirstWord() {
        assertEquals("reload", ServerCommandGate.nameOf("/reload"));
        assertEquals("reload", ServerCommandGate.nameOf("/RELOAD"));
        assertEquals("reload", ServerCommandGate.nameOf("   /reload   "));
        assertEquals("compact", ServerCommandGate.nameOf("/compact preview"));
        assertEquals("compact", ServerCommandGate.nameOf("/compact\tpreview"));
        assertEquals("", ServerCommandGate.nameOf("reload"));
        assertEquals("", ServerCommandGate.nameOf("/"));
        assertEquals("", ServerCommandGate.nameOf(null));
    }

    @Test
    @DisplayName("带参数也不放过：/delete 的参数不受按 id 寻址保护，因此连命令一起拒")
    void check_should_reject_whenDeniedCommandCarriesArguments() {
        ApiException error = assertThrows(ApiException.class,
                () -> ServerCommandGate.check("/delete s-other"));

        assertEquals(Responses.FORBIDDEN, error.getStatus());
        assertEquals("COMMAND_NOT_ALLOWED", error.getCode());
    }
}
