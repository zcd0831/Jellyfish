package io.github.zcd0831.jellyfish.boot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 只有两个启动参数：数据目录与日志级别。 */
class BootOptionsTest {

    @Test
    void 缺省数据目录在用户主目录下() {
        BootOptions options = BootOptions.parse(new String[0]);

        assertEquals(System.getProperty("user.home") + "/.jellyfish", options.dataDir());
        assertEquals("info", options.logLevel());
    }

    @Test
    void 两个参数都能覆盖() {
        BootOptions options = BootOptions.parse(new String[]{"--data-dir", "/tmp/jf", "--log-level", "debug"});

        assertEquals("/tmp/jf", options.dataDir());
        assertEquals("debug", options.logLevel());
    }

    @Test
    void 未知参数直接拒绝() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> BootOptions.parse(new String[]{"--resume", "abc"}));

        assertTrue(e.getMessage().contains("未知参数"), e.getMessage());
    }

    @Test
    void 日志级别只接受五个取值() {
        assertThrows(IllegalArgumentException.class,
                () -> BootOptions.parse(new String[]{"--log-level", "verbose"}));
    }

    @Test
    void 参数缺值时按未知参数处理() {
        assertThrows(IllegalArgumentException.class,
                () -> BootOptions.parse(new String[]{"--data-dir"}));
    }

    @Test
    void 可以关掉颜色() {
        assertTrue(BootOptions.parse(new String[]{"--no-color"}).noColor());
        assertTrue(!BootOptions.parse(new String[0]).noColor());
    }
}
