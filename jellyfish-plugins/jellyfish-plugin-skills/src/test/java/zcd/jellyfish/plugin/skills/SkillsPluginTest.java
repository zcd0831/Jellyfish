package zcd.jellyfish.plugin.skills;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CommandArguments;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PromptContributionRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.plugin.PluginContextImpl;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillsPlugin} 的单元测试：只验证「注册了哪些扩展点、owner 是谁、禁用时的边界」。
 * <p>
 * 用真实的 {@link PluginContextImpl} 与真实注册表（而不是 mock 上下文）：本类的职责就是把处理器
 * 交给上下文，用真实上下文能顺带验证它确实落到了同一份注册表上。
 *
 * @author zcd
 */
@DisplayName("skills 插件装配")
class SkillsPluginTest {

    /** 内核里本插件的标识，与 plugin.properties 保持一致。 */
    private static final String PLUGIN_ID = "jellyfish-skills";

    /** 临时根目录。 */
    @TempDir
    Path root;

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
    }

    @AfterEach
    void tearDown() {
        events.close();
    }

    @Test
    @DisplayName("启用时注册工具、清单贡献与 /skills 命令，且 owner 归本插件")
    void start_should_registerToolContributionAndCommand() {
        // When
        new SkillsPlugin().start(context(enabledConfiguration()));

        // Then
        assertEquals(1, extensions.handlers(ToolCallRequest.class, SkillTool.NAME).size());
        assertEquals(1, extensions.bindings(PromptContributionRequest.class, null).size());
        assertEquals(PLUGIN_ID,
                extensions.bindings(PromptContributionRequest.class, null).get(0).getOwner());
        assertEquals(1, extensions.handlers(CommandRequest.class, "skills").size());
    }

    @Test
    @DisplayName("禁用时不注册工具与清单贡献，但保留 /skills 台账")
    void start_should_registerOnlyLedger_when_disabled() {
        // Given
        Map<String, Object> values = enabledConfiguration();
        values.put(SkillsConfig.KEY_ENABLED, Boolean.FALSE);

        // When
        new SkillsPlugin().start(context(values));

        // Then：「为什么什么都看不见」的唯一答案必须留在能看见的地方
        assertTrue(extensions.handlers(ToolCallRequest.class, SkillTool.NAME).isEmpty());
        assertTrue(extensions.bindings(PromptContributionRequest.class, null).isEmpty());
        assertEquals(1, extensions.handlers(CommandRequest.class, "skills").size());
    }

    @Test
    @DisplayName("/skills 命令应可执行并返回台账文本")
    void command_should_renderLedger() throws Exception {
        // Given
        new SkillsPlugin().start(context(enabledConfiguration()));
        ExtensionHandler<CommandRequest, CommandResult> handler =
                extensions.handler(CommandRequest.class, "skills");

        // When
        String output = handler.handle(new CommandRequest("skills", CommandArguments.EMPTY, "s1"))
                .getOutput();

        // Then
        assertTrue(output.contains("根目录:"));
        assertTrue(output.contains(root.toString()));
    }

    @Test
    @DisplayName("配置非法时应在启动期就抛：不让它变成「清单怎么是空的」这种隐式失效")
    void start_should_throw_when_configInvalid() {
        // Given
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(SkillsConfig.KEY_ROOTS, Collections.singletonList(root.toString()));
        values.put(SkillsConfig.KEY_MAX_SKILLS, "很多");

        // When / Then
        assertThrows(JellyfishException.class, () -> new SkillsPlugin().start(context(values)));
    }

    /**
     * 构造只指向临时根目录的启用配置。
     *
     * @return 配置
     */
    private Map<String, Object> enabledConfiguration() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put(SkillsConfig.KEY_ROOTS, Collections.singletonList(root.toString()));
        return values;
    }

    /**
     * 构造插件上下文。
     *
     * @param configuration 配置段
     * @return 上下文
     */
    private PluginContext context(Map<String, Object> configuration) {
        return new PluginContextImpl(PluginDeclaration.of(PLUGIN_ID, configuration), extensions, events);
    }
}
