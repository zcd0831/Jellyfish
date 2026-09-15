package zcd.jellyfish.infra.command;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.extension.CommandArguments;
import zcd.jellyfish.api.extension.CommandChoice;
import zcd.jellyfish.api.extension.CommandDescriptor;
import zcd.jellyfish.api.extension.CommandOptionRequest;
import zcd.jellyfish.api.extension.CommandOptions;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link CommandManager} 的单元测试：验证语法判定、名字与别名解析、分发、清单与帮助。
 * <p>
 * 这里用真实的 {@link TypeRegistry} 与 {@link ExtensionRegistry}：命令域的全部价值就在于
 * 「经注册表取处理器」，把注册表 mock 掉等于什么都没测。
 *
 * @author zcd
 */
class CommandManagerTest {

    /** 共用注册表。 */
    private final TypeRegistry registry = new TypeRegistry();

    /** 同步扩展点策略。 */
    private final ExtensionRegistry extensions = new ExtensionRegistry(registry);

    /** 被测命令域服务。 */
    private final CommandManager manager = new CommandManager(extensions);

    @Test
    void constructor_should_reject_null_registry() {
        // When / Then
        assertThrows(NullPointerException.class, () -> new CommandManager(null));
    }

    @Test
    void isCommand_should_only_check_syntax() {
        // Then：没注册过的命令也算「语法上是命令」，有没有实现由 execute 回答
        assertTrue(manager.isCommand("/help"));
        assertFalse(manager.isCommand("help"));
        assertFalse(manager.isCommand("/"));
    }

    @Test
    void execute_should_return_unknown_without_touching_registry_when_input_is_not_command() {
        // Given：注册表里塞一个类型不对的描述符，任何清单查询都会抛异常
        extensions.handle("plugin-a", CommandRequest.class, "calc", "not-a-descriptor",
                request -> CommandResult.ok("ok"), RegisterOptions.DEFAULT);

        // When
        CommandResult result = manager.execute("hello", "session-1");

        // Then：非命令走语法判定，不查注册表（否则这里会是 ERROR）
        assertEquals(CommandResult.Kind.UNKNOWN, result.getKind());
    }

    @Test
    void execute_should_return_error_when_arguments_are_malformed() {
        // Given
        register("plugin-a", "m", null, request -> CommandResult.ok("m"));

        // When
        CommandResult result = manager.execute("/m \"abc");

        // Then
        assertEquals(CommandResult.Kind.ERROR, result.getKind());
        assertTrue(result.getOutput().contains("引号未闭合"));
    }

    @Test
    void execute_should_dispatch_arguments_and_session_to_registered_handler() {
        // Given
        AtomicReference<CommandRequest> received = new AtomicReference<CommandRequest>();
        register("plugin-a", "agent", new CommandDescriptor("切换 agent", "<agentId>", Arrays.asList("a")),
                request -> {
                    received.set(request);
                    return CommandResult.ok("已切换");
                });

        // When
        CommandResult result = manager.execute("/agent coder extra", "session-1");

        // Then
        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertEquals("已切换", result.getOutput());
        assertEquals("agent", received.get().getName());
        assertEquals(Arrays.asList("coder", "extra"), received.get().getArguments().getTokens());
        assertEquals("session-1", received.get().getSessionId());
    }

    @Test
    void execute_should_resolve_alias_to_the_same_handler() {
        // Given
        AtomicInteger calls = new AtomicInteger();
        register("plugin-a", "help", new CommandDescriptor("帮助", "[命令]", Arrays.asList("h", "?")),
                request -> {
                    calls.incrementAndGet();
                    return CommandResult.ok("help");
                });

        // When
        CommandResult result = manager.execute("/h");

        // Then
        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertEquals(1, calls.get());
    }

    @Test
    void execute_should_return_unknown_for_unregistered_command() {
        // Given
        register("plugin-a", "help", null, request -> CommandResult.ok("help"));

        // When
        CommandResult result = manager.execute("/missing");

        // Then
        assertEquals(CommandResult.Kind.UNKNOWN, result.getKind());
        assertTrue(result.getOutput().contains("/help"));
    }

    @Test
    void execute_should_return_error_when_handler_throws() {
        // Given
        register("plugin-a", "boom", null, request -> {
            throw new IllegalStateException("炸了");
        });

        // When
        CommandResult result = manager.execute("/boom");

        // Then：同步侧没有护栏，异常处置是调用点的责任
        assertEquals(CommandResult.Kind.ERROR, result.getKind());
        assertTrue(result.getOutput().contains("炸了"));
    }

    @Test
    void execute_should_map_null_handler_result_to_ok_without_output() {
        // Given
        register("plugin-a", "silent", null, request -> null);

        // When
        CommandResult result = manager.execute("/silent");

        // Then
        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertNull(result.getOutput());
    }

    @Test
    void execute_should_return_error_when_handler_lookup_is_ambiguous() {
        // Given：具名命令 + 类型级注册，任何名字的查找都会多命中
        AtomicInteger calls = new AtomicInteger();
        register("plugin-a", "calc", null, request -> {
            calls.incrementAndGet();
            return CommandResult.ok("named");
        });
        extensions.contribute("plugin-b", CommandRequest.class, null, request -> {
            calls.incrementAndGet();
            return CommandResult.ok("wide");
        }, RegisterOptions.DEFAULT);

        // When
        CommandResult result = manager.execute("/calc");

        // Then：多命中说明有人用错了注册入口，当场暴露且不执行任何处理器
        assertEquals(CommandResult.Kind.ERROR, result.getKind());
        assertEquals(0, calls.get());
    }

    @Test
    void execute_should_return_error_when_descriptor_type_mismatches() {
        // Given
        extensions.handle("plugin-a", CommandRequest.class, "calc", "not-a-descriptor",
                request -> CommandResult.ok("ok"), RegisterOptions.DEFAULT);

        // When
        CommandResult result = manager.execute("/calc");

        // Then：清单读不出来时报清单故障，而不是把插件缺陷伪装成「未知命令」
        assertEquals(CommandResult.Kind.ERROR, result.getKind());
        assertTrue(result.getOutput().contains("命令清单读取失败"));
        assertTrue(manager.renderHelp().contains("命令清单读取失败"));
    }

    @Test
    void execute_should_report_alias_ambiguity_with_candidates() {
        // Given：两条命令声明了同一个别名
        register("plugin-a", "model", new CommandDescriptor("切模型", null, Arrays.asList("m")),
                request -> CommandResult.ok("model"));
        register("plugin-a", "mode", new CommandDescriptor("切模式", null, Arrays.asList("m")),
                request -> CommandResult.ok("mode"));

        // When
        CommandResult result = manager.execute("/m");

        // Then
        assertEquals(CommandResult.Kind.ERROR, result.getKind());
        assertTrue(result.getOutput().contains("/model"));
        assertTrue(result.getOutput().contains("/mode"));
    }

    @Test
    void execute_should_prefer_command_name_over_alias() {
        // Given："mode" 既是命令名，又是 "model" 的别名
        register("plugin-a", "mode", new CommandDescriptor("切模式", null, null),
                request -> CommandResult.ok("mode"));
        register("plugin-a", "model", new CommandDescriptor("切模型", null, Arrays.asList("mode")),
                request -> CommandResult.ok("model"));

        // When
        CommandResult result = manager.execute("/mode");

        // Then：命令名优先，该别名静默不可达
        assertEquals("mode", result.getOutput());
    }

    @Test
    void execute_should_see_registrations_made_after_construction() {
        // Given：不建索引、不缓存，热部署后立刻可见
        assertEquals(CommandResult.Kind.UNKNOWN, manager.execute("/late").getKind());

        // When
        register("plugin-a", "late", null, request -> CommandResult.ok("late"));

        // Then
        assertEquals(CommandResult.Kind.OK, manager.execute("/late").getKind());
    }

    @Test
    void execute_should_stop_seeing_commands_after_unregister() {
        // Given
        register("plugin-a", "help", null, request -> CommandResult.ok("help"));

        // When
        extensions.unregisterAll("plugin-a");

        // Then
        assertEquals(CommandResult.Kind.UNKNOWN, manager.execute("/help").getKind());
    }

    @Test
    void execute_should_only_call_the_matched_handler() {
        // Given
        AtomicInteger otherCalls = new AtomicInteger();
        register("plugin-a", "help", null, request -> CommandResult.ok("help"));
        register("plugin-a", "other", null, request -> {
            otherCalls.incrementAndGet();
            return CommandResult.ok("other");
        });

        // When
        manager.execute("/help");

        // Then
        assertEquals(0, otherCalls.get());
    }

    @Test
    void structured_entry_should_hit_the_same_handler_as_raw_input() {
        // Given
        AtomicReference<CommandRequest> received = new AtomicReference<CommandRequest>();
        register("plugin-a", "agent", new CommandDescriptor("切换 agent", "<agentId>", Arrays.asList("a")),
                request -> {
                    received.set(request);
                    return CommandResult.ok("ok");
                });

        // When：Web / TUI 直接给命令名与结构化参数，不必拼成一行原文
        CommandResult result = manager.execute("a", new CommandArguments(Arrays.asList("coder"), "coder"),
                "session-1");

        // Then：别名解析、参数与会话透传与原文入口完全一致
        assertEquals(CommandResult.Kind.OK, result.getKind());
        assertEquals("agent", received.get().getName());
        assertEquals(Arrays.asList("coder"), received.get().getArguments().getTokens());
        assertEquals("session-1", received.get().getSessionId());
    }

    @Test
    void structured_entry_should_normalize_null_arguments() {
        // Given
        AtomicReference<CommandRequest> received = new AtomicReference<CommandRequest>();
        register("plugin-a", "help", null, request -> {
            received.set(request);
            return CommandResult.ok("help");
        });

        // When
        manager.execute("help", null, null);

        // Then
        assertTrue(received.get().getArguments().isEmpty());
        assertNull(received.get().getSessionId());
    }

    @Test
    void structured_entry_should_report_unknown_when_name_is_blank() {
        // When / Then
        assertEquals(CommandResult.Kind.UNKNOWN,
                manager.execute("  ", CommandArguments.EMPTY, null).getKind());
    }

    @Test
    void commands_should_be_sorted_by_name_and_include_commands_without_descriptor() {
        // Given
        register("plugin-a", "zeta", new CommandDescriptor("Z", null, null), request -> CommandResult.ok("z"));
        register("plugin-a", "alpha", null, request -> CommandResult.ok("a"));

        // When
        List<CommandInfo> infos = manager.commands();

        // Then
        assertEquals(2, infos.size());
        assertEquals("alpha", infos.get(0).getName());
        assertEquals("zeta", infos.get(1).getName());
        assertNull(infos.get(0).getDescriptor());
        assertTrue(infos.get(0).getAliases().isEmpty());
        assertEquals("Z", infos.get(1).getSummary());
    }

    @Test
    void commands_should_be_unmodifiable_and_empty_without_registration() {
        // When / Then
        assertTrue(manager.commands().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> manager.commands().add(null));
    }

    @Test
    void renderHelp_should_render_usage_summary_and_aliases() {
        // Given
        register("plugin-a", "agent", new CommandDescriptor("切换 agent", "<agentId>", Arrays.asList("a")),
                request -> CommandResult.ok("ok"));

        // When
        String help = manager.renderHelp();

        // Then
        assertTrue(help.startsWith("可用命令（1 条）："));
        assertTrue(help.contains("/agent <agentId>"));
        assertTrue(help.contains("切换 agent"));
        assertTrue(help.contains("（别名：/a）"));
    }

    @Test
    void renderHelp_should_use_placeholder_when_command_has_no_descriptor() {
        // Given
        register("plugin-a", "plain", null, request -> CommandResult.ok("ok"));

        // When
        String help = manager.renderHelp();

        // Then：没有名片的命令一样要出现在清单里
        assertTrue(help.contains("/plain"));
        assertTrue(help.contains("（未提供说明）"));
    }

    @Test
    void renderHelp_should_report_when_no_command_registered() {
        // When / Then
        assertEquals("当前没有任何可用命令。", manager.renderHelp());
    }

    @Test
    void renderHelp_with_name_or_alias_should_render_single_command() {
        // Given
        register("plugin-a", "help", new CommandDescriptor("显示帮助", "[命令]", Arrays.asList("h")),
                request -> CommandResult.ok("ok"));

        // When
        String byName = manager.renderHelp("help");
        String byAlias = manager.renderHelp("h");

        // Then
        assertTrue(byName.startsWith("/help [命令]"));
        assertTrue(byName.contains("显示帮助"));
        assertTrue(byName.contains("别名：/h"));
        assertEquals(byName, byAlias);
    }

    @Test
    void renderHelp_with_unknown_query_should_suggest_full_help() {
        // When
        String help = manager.renderHelp("ghost");

        // Then
        assertTrue(help.contains("未找到命令"));
        assertTrue(help.contains("/help"));
    }

    @Test
    void renderHelp_with_ambiguous_alias_should_list_candidates() {
        // Given
        register("plugin-a", "model", new CommandDescriptor("切模型", null, Arrays.asList("m")),
                request -> CommandResult.ok("model"));
        register("plugin-a", "mode", new CommandDescriptor("切模式", null, Arrays.asList("m")),
                request -> CommandResult.ok("mode"));

        // When
        String help = manager.renderHelp("m");

        // Then
        assertTrue(help.contains("/model"));
        assertTrue(help.contains("/mode"));
    }

    /**
     * 注册一条命令。
     *
     * @param owner      来源
     * @param name       命令名
     * @param descriptor 命令名片，可为 {@code null}
     * @param handler    处理器
     */
    private void register(String owner, String name, CommandDescriptor descriptor,
                          ExtensionHandler<CommandRequest, CommandResult> handler) {
        extensions.handle(owner, CommandRequest.class, name, descriptor, handler, RegisterOptions.DEFAULT);
    }

    @Test
    void options_should_return_choices_without_executing_the_command() {
        // Given：执行处理器与候选处理器分开注册，执行处理器绝不该被调用
        AtomicInteger executed = new AtomicInteger();
        register("plugin-a", "agent", new CommandDescriptor("切 agent", "[agentId]", Arrays.asList("a")),
                request -> {
                    executed.incrementAndGet();
                    return CommandResult.ok("切了");
                });
        registerOptions("plugin-a", "agent", request -> CommandOptions.of(Arrays.asList(
                new CommandChoice("coder", "coder", "写代码", true),
                new CommandChoice("writer", "writer", null, false))));

        // When
        List<CommandChoice> options = manager.options("agent", "session-1");

        // Then
        assertEquals(2, options.size());
        assertEquals("coder", options.get(0).getValue());
        assertTrue(options.get(0).isCurrent());
        assertEquals(0, executed.get());
    }

    @Test
    void options_should_resolve_alias() {
        // Given
        register("plugin-a", "agent", new CommandDescriptor("切 agent", null, Arrays.asList("a")),
                request -> CommandResult.ok("ok"));
        registerOptions("plugin-a", "agent", request -> CommandOptions.of(Arrays.asList(
                new CommandChoice("coder", "coder"))));

        // When / Then
        assertEquals(1, manager.options("a", null).size());
    }

    @Test
    void options_should_be_empty_when_no_option_handler_registered() {
        // Given
        register("plugin-a", "help", null, request -> CommandResult.ok("help"));

        // When / Then：候选只是输入辅助，没有就是空列表
        assertTrue(manager.options("help", null).isEmpty());
        assertTrue(manager.options("missing", null).isEmpty());
        assertTrue(manager.options(null, null).isEmpty());
    }

    @Test
    void options_should_be_empty_when_option_handler_throws() {
        // Given
        register("plugin-a", "agent", null, request -> CommandResult.ok("ok"));
        registerOptions("plugin-a", "agent", request -> {
            throw new IllegalStateException("炸了");
        });

        // When / Then：同步侧没有护栏，调用点把异常当成「没有候选」而不是上抛
        assertTrue(manager.options("agent", null).isEmpty());
    }

    @Test
    void options_should_be_empty_when_option_handler_is_ambiguous() {
        // Given：具名候选 + 类型级贡献，查找会多命中
        register("plugin-a", "agent", null, request -> CommandResult.ok("ok"));
        registerOptions("plugin-a", "agent", request -> CommandOptions.of(Arrays.asList(
                new CommandChoice("coder", "coder"))));
        extensions.contribute("plugin-b", CommandOptionRequest.class, null,
                request -> CommandOptions.of(Arrays.asList(new CommandChoice("wide", "wide"))),
                RegisterOptions.DEFAULT);

        // When / Then：多命中属于注册缺陷，当作「没有候选」而不是任选一个
        assertTrue(manager.options("agent", null).isEmpty());
    }

    /**
     * 注册一条命令的只读候选处理器。
     *
     * @param owner   来源
     * @param name    命令名
     * @param handler 候选处理器
     */
    private void registerOptions(String owner, String name,
                                 ExtensionHandler<CommandOptionRequest, CommandOptions> handler) {
        extensions.handle(owner, CommandOptionRequest.class, name, null, handler, RegisterOptions.DEFAULT);
    }
}
