package zcd.jellyfish.script;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.extension.CommandDescriptor;
import zcd.jellyfish.api.extension.CommandOptionRequest;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.PromptContribution;
import zcd.jellyfish.api.extension.PromptContributionRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.api.plugin.PluginOwnerNamespace;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.event.EventChannelOptions;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.plugin.PluginContextFactory;
import zcd.jellyfish.infra.registry.TypeRegistry;
import zcd.jellyfish.script.codec.ExtensionCodecs;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脚本注册器的单元测试：把一份清单变成内核注册表里的转发处理器。
 * <p>
 * 这是「脚本与 Java 插件同构」最实际的验证点——注册走的是真实的 {@code PluginContext} 与
 * {@code ExtensionRegistry}，处理器由真实的注册表取出并真的调用一次，走的正是内核在工具调用点
 * 那条路径。在测试里另造一套假注册表就没有这个价值了：那样验证的是「我以为内核会怎么查」。
 * <p>
 * 另外两条刻意的性质也在这里钉住：一条注册失败不影响其余条目（否则一个人的错误会惩罚
 * 整门语言的所有脚本）；注册结果保留注册句柄，使「按脚本治理能力」在将来仍然可能。
 *
 * @author zcd
 */
@DisplayName("脚本注册器")
class ScriptRegistrarTest {

    /** 桥接插件标识，模拟真实的 owner 根。 */
    private static final String BRIDGE_ID = "jellyfish-plugin-python";

    /** 共用注册表。 */
    private final TypeRegistry registry = new TypeRegistry();

    /** 同步扩展点策略。 */
    private final ExtensionRegistry extensions = new ExtensionRegistry(registry);

    /** 事件通道。 */
    private final EventChannel events = new EventChannel(EventChannelOptions.defaults(), registry);

    /** 上下文工厂。 */
    private final PluginContextFactory factory = new PluginContextFactory(extensions, events, registry);

    @Test
    @DisplayName("工具应按路由键注册，处理器转发到脚本并在返回时解出结果")
    void register_should_registerToolAndForwardToScript() {
        ScriptRegistrar registrar = new ScriptRegistrar(ExtensionCodecs.DEFAULTS, (plugin, typeName, request) -> {
            assertEquals("jira", plugin.id());
            assertEquals("tool", typeName);
            return ScriptJson.tree("{\"output\":\"ok\"}");
        });

        ScriptRegistration registration = registrar.register(pluginContext(), script("jira",
                "{\"entry\":\"m.py\",\"tools\":[{\"name\":\"jira_issue\",\"readOnly\":true}]}"));

        assertEquals(1, registration.registeredCount());
        List<ToolDescriptor> descriptors = extensions.descriptors(ToolCallRequest.class, ToolDescriptor.class);
        assertEquals(1, descriptors.size());
        assertEquals("jira_issue", descriptors.get(0).getName());
        assertTrue(descriptors.get(0).isReadOnly());

        ToolCallResult result = extensions.invoke(extensions.handler(ToolCallRequest.class, "jira_issue"),
                new ToolCallRequest("jira_issue", null, "s-1"));
        assertEquals("ok", result.getOutput());
    }

    @Test
    @DisplayName("hasOptions 应在同一条命令名下自动挂上候选查询处理器")
    void register_should_registerCommandOptions_when_hasOptionsIsTrue() {
        ScriptRegistrar registrar = new ScriptRegistrar(ExtensionCodecs.DEFAULTS, fixedResult("{\"choices\":[]}"));

        ScriptRegistration registration = registrar.register(pluginContext(), script("jira",
                "{\"entry\":\"m.py\",\"commands\":[{\"name\":\"jira\","
                        + "\"descriptor\":{\"summary\":\"操作 Jira\"},\"hasOptions\":true}]}"));

        assertEquals(2, registration.registeredCount());
        assertEquals(1, extensions.handlers(CommandRequest.class, "jira").size());
        assertEquals(1, extensions.handlers(CommandOptionRequest.class, "jira").size());
        CommandDescriptor descriptor = extensions.descriptors(CommandRequest.class, CommandDescriptor.class).get(0);
        assertEquals("操作 Jira", descriptor.getSummary());
    }

    @Test
    @DisplayName("commandOptions 应能独立声明候选查询，与 Java 插件的注册入口对齐")
    void register_should_registerCommandOptions_when_declaredExplicitly() {
        ScriptRegistrar registrar = new ScriptRegistrar(ExtensionCodecs.DEFAULTS, fixedResult("{}"));

        ScriptRegistration registration = registrar.register(pluginContext(), script("notes",
                "{\"entry\":\"m.py\",\"commands\":[{\"name\":\"notes\"}],"
                        + "\"commandOptions\":[{\"name\":\"notes\"}]}"));

        assertEquals(2, registration.registeredCount());
        assertEquals(1, extensions.handlers(CommandOptionRequest.class, "notes").size());
    }

    @Test
    @DisplayName("类型级贡献应按类型注册，而不是按路由键")
    void register_should_registerContributionAsTypeLevel() {
        ScriptRegistrar registrar = new ScriptRegistrar(ExtensionCodecs.DEFAULTS,
                fixedResult("{\"text\":\"待办 2/5\"}"));

        ScriptRegistration registration = registrar.register(pluginContext(),
                script("jira", "{\"entry\":\"m.py\",\"contributions\":[\"prompt\"]}"));

        assertEquals(1, registration.registeredCount());
        PromptContribution contribution = extensions.invoke(
                extensions.handlers(PromptContributionRequest.class, null).get(0),
                new PromptContributionRequest("s-1"));
        assertEquals("待办 2/5", contribution.getText());
    }

    @Test
    @DisplayName("调用失败应原样上抛，交由内核调用点处置")
    void register_should_propagateFailure_when_callerThrows() {
        ScriptRegistrar registrar = new ScriptRegistrar(ExtensionCodecs.DEFAULTS, (plugin, type, request) -> {
            throw new JellyfishException("调用入口失败: script=" + plugin.id() + " type=" + type);
        });

        registrar.register(pluginContext(), script("jira",
                "{\"entry\":\"m.py\",\"tools\":[{\"name\":\"jira_issue\"}]}"));

        JellyfishException failure = assertThrows(JellyfishException.class,
                () -> extensions.invoke(extensions.handler(ToolCallRequest.class, "jira_issue"),
                        new ToolCallRequest("jira_issue", null, "s-1")));
        // 异常只说清「哪个脚本的哪类扩展点」，工具名由内核在回灌时补上（工具执行失败：…）
        assertTrue(failure.getMessage().contains("script=jira"), failure.getMessage());
        assertTrue(failure.getMessage().contains("type=tool"), failure.getMessage());
    }

    @Test
    @DisplayName("一条注册失败不应影响其余条目，问题被收敛成记录")
    void register_should_isolateFailure_when_oneEntryConflicts() {
        // 先占掉命令名，制造一次同键冲突
        extensions.handle("plugin-x", CommandRequest.class, "jira", null,
                request -> CommandResult.ok("x"), RegisterOptions.DEFAULT);
        ScriptRegistrar registrar = new ScriptRegistrar(ExtensionCodecs.DEFAULTS, fixedResult("{}"));

        ScriptRegistration registration = registrar.register(pluginContext(), script("jira",
                "{\"entry\":\"m.py\",\"commands\":[{\"name\":\"jira\"}],\"tools\":[{\"name\":\"jira_issue\"}]}"));

        assertEquals(1, registration.registeredCount());
        assertEquals(1, registration.issues().size());
        assertTrue(registration.issues().get(0).message().contains("命令注册失败"),
                registration.issues().toString());
        assertEquals(1, extensions.handlers(ToolCallRequest.class, "jira_issue").size());
    }

    @Test
    @DisplayName("子上下文注册应落在插件命名空间下，并由一次释放连根收干净")
    void register_should_placeRegistrationsUnderNamespace_when_subContextIsUsed() {
        ScriptRegistrar registrar = new ScriptRegistrar(ExtensionCodecs.DEFAULTS, fixedResult("{}"));

        registrar.register(childContext("jira"), script("jira",
                "{\"entry\":\"m.py\",\"tools\":[{\"name\":\"one\"},{\"name\":\"two\"}]}"));

        // 注册确实挂在了 jira 的子命名空间下，而不是桥接插件自己的名义下
        assertEquals(BRIDGE_ID + PluginOwnerNamespace.SEPARATOR + "jira",
                registry.resolve(ToolCallRequest.class, "one").get(0).getOwner());
        // 框架只拿得到桥接插件标识，一次释放把子单元里的注册一并清掉
        assertEquals(2, factory.release(BRIDGE_ID));
        assertTrue(extensions.handlers(ToolCallRequest.class, "one").isEmpty());
        assertTrue(extensions.handlers(ToolCallRequest.class, "two").isEmpty());
    }

    /**
     * 造一个以桥接插件标识为 owner 的上下文。
     *
     * @return 上下文
     */
    private PluginContext pluginContext() {
        return factory.create(PluginDeclaration.of(BRIDGE_ID));
    }

    /**
     * 造一个以 {@code 桥接插件标识::脚本标识} 为 owner 的子上下文。
     *
     * @param scriptId 脚本标识
     * @return 子上下文
     */
    private PluginContext childContext(String scriptId) {
        return factory.create(PluginDeclaration.of(
                BRIDGE_ID + PluginOwnerNamespace.SEPARATOR + scriptId));
    }

    /**
     * 造一个脚本。
     *
     * @param id       脚本标识
     * @param manifest 清单正文
     * @return 脚本
     */
    private ScriptPlugin script(String id, String manifest) {
        return new ScriptPlugin(id, Paths.get("/tmp/scripts").resolve(id),
                ScriptManifest.parse(manifest, id, ExtensionCodecs.DEFAULTS));
    }

    /**
     * 造一个总是返回同一载荷的调用入口。
     *
     * @param json 结果载荷
     * @return 调用入口
     */
    private static ScriptCaller fixedResult(String json) {
        return (plugin, typeName, request) -> ScriptJson.tree(json);
    }
}
