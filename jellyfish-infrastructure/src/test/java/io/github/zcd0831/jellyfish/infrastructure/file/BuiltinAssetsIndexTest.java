package io.github.zcd0831.jellyfish.infrastructure.file;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `builtin/index.txt` 是唯一入口：漏列一个文件，那个内置资产就等于不存在
 * （`replan_plan` 与 `read_artifact` 就这样"写好了但没装配上"）。
 * 规则出处：09-infrastructure §9.5（内置代理不落盘，按清单装载）。
 */
class BuiltinAssetsIndexTest {

    @Test
    void 目录里的每个内置资产都在index里() throws Exception {
        Path root = builtinRoot();
        List<String> indexed = BuiltinAssets.list("");
        List<String> missing = new ArrayList<String>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                String relative = root.relativize(file).toString().replace('\\', '/');
                if ("index.txt".equals(relative)) {
                    continue;
                }
                if (!indexed.contains(relative)) {
                    missing.add(relative);
                }
            }
        }
        Collections.sort(missing);
        assertTrue(missing.isEmpty(), "index.txt 漏列了：" + missing);
    }

    @Test
    void 计划与判定要用的内置工具都装得上() {
        List<String> tools = BuiltinAssets.list("tools/");

        assertTrue(tools.contains("tools/replan_plan.json"), "replan_plan 不在清单里：" + tools);
        assertTrue(tools.contains("tools/read_artifact.json"), "read_artifact 不在清单里：" + tools);
    }

    /** 测试跑在 target/classes 下，按 classpath 定位资源目录即可（打包成 jar 后不再走这条路径）。 */
    private static Path builtinRoot() throws Exception {
        URL index = BuiltinAssets.class.getResource("/builtin/index.txt");
        assertTrue(index != null, "找不到 /builtin/index.txt");
        Path path = Paths.get(index.toURI());
        if (!"file".equals(index.getProtocol())) {
            throw new IOException("内置资产不是目录形式，跳过（" + index + "）");
        }
        return path.getParent();
    }
}
