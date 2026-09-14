package zcd.jellyfish.cli.di;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.agent.AgentRegistry;
import zcd.jellyfish.infra.config.AppConfig;
import zcd.jellyfish.infra.config.ConfigLoader;
import zcd.jellyfish.infra.config.ConfigPaths;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.config.SettingsBinder;
import zcd.jellyfish.infra.config.SettingsReader;
import zcd.jellyfish.infra.permission.PermissionPolicyProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentModule} 的单元测试：验证策略来源确实绑到 AgentManager，且绑定后行为不变。
 * <p>
 * Dagger 的 {@code @Provides} 都是包私有静态方法，因此可以像普通方法一样直接断言，
 * 不需要启动组件（组件会读取 classpath 配置并创建 HTTP 客户端，不适合放进单元测试）。
 * <p>
 * 刻意不引入 mock：这条链路上真正的契约是「配置 → AgentManager → 策略」，用真实协作者 + 临时文件
 * 断言才有意义——这与 {@code RuntimeConfigTest} 的取向一致。
 *
 * @author zcd
 */
class AgentModuleTest {

    /** 临时目录，用于构造真实的 agents.json。 */
    @TempDir
    Path tempDir;

    @Test
    void providePermissionPolicyProvider_should_return_the_given_agent_manager() {
        // Given
        AgentManager agentManager = newAgentManager(new ConfigPaths());

        // When
        PermissionPolicyProvider provider = AgentModule.providePermissionPolicyProvider(agentManager);

        // Then
        assertSame(agentManager, provider);
        assertTrue(provider.policyOf(null).isEmpty());
    }

    @Test
    void providePermissionPolicyProvider_should_expose_policy_of_declared_agent() throws IOException {
        // Given：agents.json 里声明一个拒绝 bash 的 agent
        Path agents = tempDir.resolve("agents.json");
        Files.write(agents, ("{\"defaultAgent\":\"coder\",\"agents\":{\"coder\":"
                + "{\"permissions\":{\"deniedTools\":[\"bash\"]}}}}").getBytes(StandardCharsets.UTF_8));
        AgentManager agentManager = newAgentManager(pathsTo(agents));

        // When
        PermissionPolicyProvider provider = AgentModule.providePermissionPolicyProvider(agentManager);

        // Then
        assertTrue(provider.policyOf("coder").denies("bash"));
        assertTrue(provider.policyOf("ghost").isEmpty());
    }

    /**
     * 构造真实协作者串起来的 AgentManager（配置由临时文件提供）。
     *
     * @param agentPaths agent 段双源路径
     * @return AgentManager 实例
     */
    private AgentManager newAgentManager(ConfigPaths agentPaths) {
        AppConfig appConfig = new AppConfig(null, new ConfigPaths(), agentPaths, new ConfigPaths());
        RuntimeConfig runtimeConfig = new RuntimeConfig(appConfig,
                new ConfigLoader(new SettingsReader(), new SettingsBinder()), event -> {
                });
        runtimeConfig.refresh();
        return new AgentManager(runtimeConfig, new AgentRegistry(event -> {
        }), event -> {
        });
    }

    /**
     * 构造只指向项目级文件的双源路径。
     *
     * @param project 项目级文件路径
     * @return 双源路径
     */
    private static ConfigPaths pathsTo(Path project) {
        ConfigPaths paths = new ConfigPaths();
        paths.setProjectPath(project.toString());
        return paths;
    }
}
