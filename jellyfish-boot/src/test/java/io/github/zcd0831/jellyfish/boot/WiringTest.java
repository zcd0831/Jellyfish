package io.github.zcd0831.jellyfish.boot;

import io.github.zcd0831.jellyfish.di.AppComponent;
import io.github.zcd0831.jellyfish.di.DaggerAppComponent;
import io.github.zcd0831.jellyfish.di.InfrastructureModule;
import io.github.zcd0831.jellyfish.infrastructure.file.DataDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 装配自检：S0 暴露的每个端口都能从组件拿到实现。
 * 规则出处：技术决策 D04（装配层自检）。
 */
class WiringTest {

    @TempDir
    Path tmp;

    @Test
    void 组件提供全部已实现端口() {
        AppComponent component = DaggerAppComponent.builder()
                .infrastructureModule(new InfrastructureModule(DataDirectory.of(tmp.toString())))
                .build();

        assertNotNull(component.sessionRepository());
        assertNotNull(component.recordStore());
        assertNotNull(component.commitScope());
        assertNotNull(component.instanceLock());
        assertNotNull(component.schemaMigrator());
        assertNotNull(component.jsonCodec());
    }
}
