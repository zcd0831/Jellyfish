package io.github.zcd0831.jellyfish.domain.action.plugin;

import io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation;
import io.github.zcd0831.jellyfish.domain.shared.PluginId;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 插件授权意图：granted ⊆ declared、信任只读（05-action §5.8.6）。 */
class PluginManifestTest {

    @Test
    void 安装只落意图且默认不信任() {
        PluginManifest manifest = manifest(false, "Clock", "TokenCounter");

        assertTrue(manifest.grantedCapabilities().isEmpty(), "装完还没有任何效果");
        assertTrue(manifest.toolIds().contains(ToolId.of("echo")));
    }

    @Test
    void 授权不能超出声明() {
        PluginManifest manifest = manifest(false, "Clock");

        assertThrows(DomainInvariantViolation.class, () -> manifest.grant("ModelInvocationPort"));
        manifest.grant("Clock");
        assertEquals(1, manifest.grantedCapabilities().size());
    }

    @Test
    void 可以收回授权() {
        PluginManifest manifest = manifest(false, "Clock");
        manifest.grant("Clock");

        manifest.revoke("Clock");

        assertTrue(manifest.grantedCapabilities().isEmpty());
    }

    @Test
    void 信任在安装时确定() {
        assertTrue(manifest(true, "Clock").isTrusted());
    }

    private static PluginManifest manifest(boolean trusted, String... declared) {
        return PluginManifest.create(PluginId.of("demo.echo"), "1.0.0", 1,
                new LinkedHashSet<ToolId>(Arrays.asList(ToolId.of("echo"))),
                new LinkedHashSet<String>(Arrays.asList(declared)), trusted, new Date(1L));
    }
}
