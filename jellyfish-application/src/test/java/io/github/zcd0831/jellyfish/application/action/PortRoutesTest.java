package io.github.zcd0831.jellyfish.application.action;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 替换整张表必须是原子的：插件启停瞬间的 `resolve()` 不能读到"半新半旧"。
 * 规则出处：08-application §8.11（端口路由表共享可变，替换原子）。
 */
class PortRoutesTest {

    private static Map<String, Object> generation(String tag) {
        Map<String, Object> table = new LinkedHashMap<String, Object>();
        table.put("llm", "llm-" + tag);
        table.put("tool", "tool-" + tag);
        table.put("memory", "memory-" + tag);
        return table;
    }

    @Test
    void 替换中途不允许读到半新半旧的表() throws Exception {
        final PortRoutes routes = new PortRoutes();
        routes.replaceAll(generation("A"));
        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicInteger mixed = new AtomicInteger();
        final AtomicInteger reads = new AtomicInteger();

        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                String tag = "A";
                while (!stop.get()) {
                    tag = "A".equals(tag) ? "B" : "A";
                    routes.replaceAll(generation(tag));
                }
            }
        });
        writer.setDaemon(true);
        writer.start();
        for (int i = 0; i < 200_000; i++) {
            Map<String, Object> table = routes.snapshot();
            String llm = (String) table.get("llm");
            String tool = (String) table.get("tool");
            String memory = (String) table.get("memory");
            reads.incrementAndGet();
            if (!llm.endsWith(tool.substring(tool.length() - 1))
                    || !llm.endsWith(memory.substring(memory.length() - 1))) {
                mixed.incrementAndGet();
            }
        }
        stop.set(true);
        writer.join(2_000);

        assertTrue(reads.get() > 0);
        assertEquals(0, mixed.get(), "读到 " + mixed.get() + " 次半新半旧的表（先删后加的两步做法）");
    }

    @Test
    void 没有对应能力时回退默认实现() {
        PortRoutes routes = new PortRoutes();

        assertSame("默认", routes.resolve("llm", "默认"));
        assertEquals(0, routes.snapshot().size(), "空表就是空表，不是 null");
    }

    @Test
    void 替换后旧表不被后续修改影响() {
        PortRoutes routes = new PortRoutes();
        Map<String, Object> first = generation("A");
        routes.replaceAll(first);
        Map<String, Object> reader = routes.snapshot();

        first.put("llm", "被外部改了");
        routes.replaceAll(generation("B"));

        assertEquals("llm-A", reader.get("llm"), "拿到的快照是当时那张表，不会被别人改");
        assertEquals("llm-B", routes.resolve("llm", null));
    }
}
