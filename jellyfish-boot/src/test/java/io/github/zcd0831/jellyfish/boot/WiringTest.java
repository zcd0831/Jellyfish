package io.github.zcd0831.jellyfish.boot;

import io.github.zcd0831.jellyfish.di.AppComponent;
import io.github.zcd0831.jellyfish.di.DaggerAppComponent;
import io.github.zcd0831.jellyfish.di.InfrastructureModule;
import io.github.zcd0831.jellyfish.infrastructure.file.DataDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 装配自检：S1 暴露的每个端口/用例都能从组件拿到实现（D04）。
 */
class WiringTest {

    @TempDir
    Path tmp;

    private AppComponent component() {
        return DaggerAppComponent.builder()
                .infrastructureModule(new InfrastructureModule(DataDirectory.of(tmp.toString())))
                .shellModule(new io.github.zcd0831.jellyfish.di.ShellModule(
                        new java.io.ByteArrayInputStream(new byte[0]),
                        new PrintStream(new ByteArrayOutputStream())))
                .build();
    }

    @Test
    void 组件提供全部已实现端口与用例() {
        AppComponent component = component();

        assertNotNull(component.sessionRepository());
        assertNotNull(component.recordStore());
        assertNotNull(component.policyRepository());
        assertNotNull(component.catalogRepository());
        assertNotNull(component.commitScope());
        assertNotNull(component.instanceLock());
        assertNotNull(component.schemaMigrator());
        assertNotNull(component.clock());
        assertNotNull(component.domainEventPublisher());
        assertNotNull(component.progressPort());
        assertNotNull(component.humanInteractionPort());
        assertNotNull(component.tokenCounter());
        assertNotNull(component.systemPromptAssembler());
        assertNotNull(component.budgetService());
        assertNotNull(component.toolExecutor());
        assertNotNull(component.startSessionUseCase());
        assertNotNull(component.runSessionUseCase());
    }

    @Test
    void 首次启动写出默认策略与内置目录() {
        AppComponent component = component();

        assertNotNull(component.policyRepository().load());
        boolean hasBuiltinAgent = false;
        for (io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition agent
                : component.catalogRepository().agents()) {
            if (agent.id().getValue().startsWith("builtin.")) {
                hasBuiltinAgent = true;
            }
        }
        boolean hasAskUser = false;
        for (io.github.zcd0831.jellyfish.domain.action.tool.ToolDefinition tool
                : component.catalogRepository().tools()) {
            if (tool.name().equals("ask_user")) {
                hasAskUser = true;
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(hasBuiltinAgent, "jar 内置代理应可读出");
        org.junit.jupiter.api.Assertions.assertTrue(hasAskUser, "内置 ask_user 工具应可读出");
    }
}
