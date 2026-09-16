package zcd.jellyfish.plugin.python;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Python 语言适配的单元测试。
 * <p>
 * 这里钉住的是三条<b>一旦改动就会静默出错</b>的约定：
 * 环境变量白名单（放宽等于把 JVM 环境整份交给不受信子进程）、强制关闭 stdout 缓冲
 * （去掉会让协议与脚本互相等死）、以及启动命令里入口必须是绝对路径
 * （否则子进程按自己的 cwd 找脚本，会莫名找不到）。
 *
 * @author zcd
 */
@DisplayName("Python 语言适配")
class PythonLanguageTest {

    /** 白名单的独立副本：与实现各写一份，改宽实现会被这条用例挡住。 */
    private static final Set<String> EXPECTED_WHITELIST = new HashSet<String>(Arrays.asList(
            "PATH", "HOME", "LANG", "PYTHONPATH", "PYTHONHOME", "VIRTUAL_ENV"));

    @Test
    @DisplayName("语言标识与人读名应稳定")
    void idAndDisplayName_should_beStable() {
        PythonLanguage language = new PythonLanguage("python3");

        assertEquals("python", language.id());
        assertEquals("Python", language.displayName());
    }

    @Test
    @DisplayName("探测命令应带版本旗标且不做任何副作用")
    void probeCommand_should_askForVersion_when_interpreterIsConfigured() {
        PythonLanguage language = new PythonLanguage("/opt/venv/bin/python3");

        assertEquals(Arrays.asList("/opt/venv/bin/python3", "--version"), language.probeCommand());
    }

    @Test
    @DisplayName("启动命令应指向网关目录下的绝对入口")
    void startCommand_should_pointToAbsoluteGatewayEntry_when_directoryIsGiven() {
        Path gatewayDirectory = Paths.get("/tmp/jellyfish-gateway");

        List<String> command = new PythonLanguage("python3").startCommand(gatewayDirectory);

        assertEquals(Arrays.asList("python3", "/tmp/jellyfish-gateway/gateway.py"), command);
    }

    @Test
    @DisplayName("环境变量应只保留白名单项，并强制关闭 stdout 缓冲")
    void environment_should_onlyKeepWhitelistedVariables_andForceUnbuffered() {
        Map<String, String> environment = new PythonLanguage("python3").environment();

        assertEquals("1", environment.get(PythonLanguage.ENV_UNBUFFERED));
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            String name = entry.getKey();
            boolean allowed = EXPECTED_WHITELIST.contains(name)
                    || name.startsWith("LC_")
                    || PythonLanguage.ENV_UNBUFFERED.equals(name);
            assertTrue(allowed, "非白名单环境变量被透传: " + name);
        }
    }

    @Test
    @DisplayName("环境变量映射应不可变，避免调用方误改后影响下一次启动")
    void environment_should_beImmutable() {
        Map<String, String> environment = new PythonLanguage("python3").environment();

        assertFalse(environment.isEmpty());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> environment.put("INJECTED", "1"));
    }
}
