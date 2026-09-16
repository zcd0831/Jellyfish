package zcd.jellyfish.plugin.project;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.PromptContributionRequest;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.plugin.PluginContextImpl;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ProjectPlugin} 的单元测试：只验证「注册了哪个扩展点、owner 是谁」。
 * <p>
 * 用真实的 {@link PluginContextImpl} 与真实注册表（而不是 mock 上下文）：本类的职责就是把处理器
 * 交给上下文，用真实上下文能顺带验证它确实落到了同一份注册表上。
 * <p>
 * 探测逻辑本身由 {@link ConventionFilesTest} 与 {@link ProjectPromptContributionTest} 覆盖，
 * 这里刻意不依赖测试进程真实的工作目录里有没有 {@code AGENTS.md}。
 *
 * @author zcd
 */
@DisplayName("项目约定插件装配")
class ProjectPluginTest {

    /** 内核里本插件的标识，与 plugin.properties 保持一致。 */
    private static final String PLUGIN_ID = "jellyfish-project";

    /** 共用注册表。 */
    private TypeRegistry registry;

    /** 同步扩展点策略。 */
    private ExtensionRegistry extensions;

    /** 事件通道。 */
    private EventChannel events;

    @BeforeEach
    void setUp() {
        registry = new TypeRegistry();
        extensions = new ExtensionRegistry(registry);
        events = new EventChannel(EventChannelOptions.defaults(), registry);
        events.start();
        PluginContext context = new PluginContextImpl(PluginDeclaration.of(PLUGIN_ID), extensions, events);
        new ProjectPlugin().start(context);
    }

    @AfterEach
    void tearDown() {
        events.close();
    }

    @Test
    @DisplayName("只注册一个提示词贡献，且 owner 归本插件")
    void start_should_registerPromptContribution() {
        assertEquals(1, extensions.bindings(PromptContributionRequest.class, null).size());
        assertEquals(PLUGIN_ID,
                extensions.bindings(PromptContributionRequest.class, null).get(0).getOwner());
    }

    @Test
    @DisplayName("配置非法时应在启动期就抛：不让它变成「内联怎么不生效」这种隐式失效")
    void start_should_throw_when_configInvalid() {
        PluginDeclaration declaration = PluginDeclaration.of(PLUGIN_ID,
                Collections.<String, Object>singletonMap(
                        PluginConfig.KEY_MAX_INLINE_BYTES, "大一点"));
        PluginContext invalid = new PluginContextImpl(declaration, extensions, events);

        assertThrows(JellyfishException.class, () -> new ProjectPlugin().start(invalid));
    }
}
