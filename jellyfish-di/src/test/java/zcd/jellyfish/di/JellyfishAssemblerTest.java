package zcd.jellyfish.di;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.ask.AskPort;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.notification.SessionClosedEvent;
import zcd.jellyfish.api.extension.CommandDescriptor;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.SessionBeforeCloseRequest;
import zcd.jellyfish.api.plugin.PluginContext;
import zcd.jellyfish.api.plugin.PluginDeclaration;
import zcd.jellyfish.api.subagent.SubAgentPort;
import zcd.jellyfish.infra.config.AppConfig;
import zcd.jellyfish.infra.config.ConfigPaths;
import zcd.jellyfish.infra.config.PluginPaths;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JellyfishAssembler} 的单元测试：手工装配与 Dagger 装配的<b>行为等价性</b>。
 * <p>
 * <b>它防的是什么</b>：同一张对象图现在有两种装法（{@link JellyfishComponent} 与
 * {@link JellyfishAssembler}）。「谁依赖谁」写错会编译不过（类型互不相同），
 * 真正会静默出事的只有一类——<b>本该唯一的实例被建了第二份</b>：例如同步扩展点与事件通道各拿一张
 * 注册表、配置告警发进一个没人订阅的通道、插件拿到的委派端口是个占位实现。
 * 这类故障在启动期毫无征兆，只在某个功能悄悄不生效时才被发现。
 * <p>
 * <b>为什么用行为断言而不是比对象引用</b>：约束的落点全在私有字段里，反射读字段会把测试绑死在
 * 实现细节上（改个字段名就红，而红的原因与约束无关）。这里改成「<b>从一个入口写、从另一个入口读</b>」：
 * 例如经 {@link JellyfishRuntime#extensionRegistry()} 注册一条命令，再从
 * {@link JellyfishRuntime#commandManager()} 执行它——两条访问器只要有任何一个拿到了第二份注册表，
 * 这条断言就会红。同一段断言<b>对两种装配各跑一遍</b>，于是它们之间的分叉也就无处藏身。
 *
 * @author zcd
 */
class JellyfishAssemblerTest {

    /** 事件等待上限（秒）：通道是异步的，断言要给它时间，但不该无谓地长。 */
    private static final long EVENT_WAIT_SECONDS = 5L;

    @Test
    void create_should_throw_when_appConfig_is_null() {
        // When / Then
        assertThrows(NullPointerException.class, () -> JellyfishAssembler.create(null));
    }

    @Test
    void both_assemblies_should_use_the_app_config_given_by_the_caller() {
        // Given：两份可辨认的配置。**给两次不同的输入**是为了排掉「固定从某个来源读」那种实现——
        // 它最多只能命中其中一份，而这里要求两次都命中调用方给的那一份
        AppConfig first = appConfigNamed("jellyfish-di-test-a");
        AppConfig second = appConfigNamed("jellyfish-di-test-b");

        // When / Then：两种装法读到的都是**那一次**给的那一份（配置来源是部署事实，由调用方决定）。
        // 这份断言钉住的正是 N-07：此前 Dagger 侧固定从 classpath 读，与手工装配侧不等价
        assertEquals("jellyfish-di-test-a",
                DaggerJellyfishComponent.builder().appConfig(first).build().appConfig().getProcessName());
        assertEquals("jellyfish-di-test-b",
                DaggerJellyfishComponent.builder().appConfig(second).build().appConfig().getProcessName());
        assertEquals("jellyfish-di-test-a",
                JellyfishAssembler.create(first).appConfig().getProcessName());
        assertEquals("jellyfish-di-test-b",
                JellyfishAssembler.create(second).appConfig().getProcessName());
    }

    /**
     * 造一份带指定进程名的配置：进程名是这份配置的可辨认标记。
     *
     * @param processName 进程名
     * @return 应用级配置，保证非 {@code null}
     */
    private static AppConfig appConfigNamed(String processName) {
        return new AppConfig(processName, new ConfigPaths(), new ConfigPaths(), new ConfigPaths(),
                new PluginPaths(null));
    }

    @Test
    void accessors_should_all_be_present_when_assembled() {
        // Given / When / Then：22 个访问器一个都不能是 null，否则外壳会在第一次用到时才发现
        forEachRuntime(assembly -> {
            assertNotNull(assembly.runtimeConfig(), "runtimeConfig");
            assertNotNull(assembly.projectConfigTrust(), "projectConfigTrust");
            assertNotNull(assembly.agentHarness(), "agentHarness");
            assertNotNull(assembly.agentManager(), "agentManager");
            assertNotNull(assembly.modelManager(), "modelManager");
            assertNotNull(assembly.llmClientFactory(), "llmClientFactory");
            assertNotNull(assembly.permissionManager(), "permissionManager");
            assertNotNull(assembly.approvalChannel(), "approvalChannel");
            assertNotNull(assembly.askChannel(), "askChannel");
            assertNotNull(assembly.commandManager(), "commandManager");
            assertNotNull(assembly.sessionManager(), "sessionManager");
            assertNotNull(assembly.sessionDefaults(), "sessionDefaults");
            assertNotNull(assembly.extensionRegistry(), "extensionRegistry");
            assertNotNull(assembly.eventChannel(), "eventChannel");
            assertNotNull(assembly.runtimeInfoHolder(), "runtimeInfoHolder");
            assertNotNull(assembly.conversationService(), "conversationService");
            assertNotNull(assembly.conversationCompactor(), "conversationCompactor");
            assertNotNull(assembly.inputDirectives(), "inputDirectives");
            assertNotNull(assembly.turnRegistry(), "turnRegistry");
            assertNotNull(assembly.shellStreams(), "shellStreams");
            assertNotNull(assembly.runEventBus(), "runEventBus");
            assertNotNull(assembly.healthCheck(), "healthCheck");
        });
    }

    @Test
    void commandManager_should_dispatch_command_registered_on_exposed_extensionRegistry() {
        // Given / When / Then：注册与分发必须落在同一份注册表上，
        // 否则表现是「命令明明注册了，执行时却说没有这条命令」
        forEachRuntime(assembly -> {
            Subscription subscription = assembly.extensionRegistry().handle("parity-test",
                    CommandRequest.class, "parity", new CommandDescriptor("等价性探针", null, null, false),
                    request -> CommandResult.ok("probe-ok"), RegisterOptions.DEFAULT);
            try {
                CommandResult result = assembly.commandManager().execute("/parity", null);
                assertEquals("probe-ok", result.getOutput());
            } finally {
                subscription.close();
            }
        });
    }

    @Test
    void sessionManager_should_publish_on_exposed_eventChannel_when_session_closed() {
        // Given：会话域服务拿到的 EventPublisher 必须是这里暴露出来的那一个通道。
        // 会话通知走异步通道，用闩锁等它到达
        forEachRuntime(assembly -> {
            assembly.eventChannel().start();
            CountDownLatch latch = new CountDownLatch(1);
            Subscription subscription = assembly.eventChannel().subscribe("parity-test",
                    SessionClosedEvent.class, event -> latch.countDown());
            try {
                String sessionId = assembly.sessionManager().create(null, null, null).getSessionId();
                assembly.sessionManager().close(sessionId, SessionBeforeCloseRequest.Reason.USER_REQUEST);
                assertTrue(awaitQuietly(latch), "没有收到会话关闭通知");
            } finally {
                subscription.close();
                assembly.eventChannel().close();
            }
        });
    }

    @Test
    void healthCheck_should_render_report_when_nothing_started() {
        // Given / When：四个检查项跨 infra 与 core 两层，装配错了这里就会空指针
        forEachRuntime(assembly -> {
            // Then
            String report = assembly.healthCheck().check().render();
            assertNotNull(report);
            assertFalse(report.trim().isEmpty(), "健康报告不应为空");
        });
    }

    @Test
    void create_should_return_independent_graphs_when_called_twice() {
        // Given：两种装法各造一对图
        JellyfishRuntime daggerFirst = DaggerJellyfishComponent.builder().appConfig(emptyAppConfig()).build();
        JellyfishRuntime daggerSecond = DaggerJellyfishComponent.builder().appConfig(emptyAppConfig()).build();
        JellyfishRuntime manualFirst = JellyfishAssembler.create(emptyAppConfig());
        JellyfishRuntime manualSecond = JellyfishAssembler.create(emptyAppConfig());

        // When / Then：只往每一对的第一张图里注册，第二张图必须看不见它——用静态字段串起来的实现会看见
        assertTrue(registerOnlyIn(daggerFirst, daggerSecond).isError(), "Dagger：两张图共享了注册表");
        assertTrue(registerOnlyIn(manualFirst, manualSecond).isError(), "手工装配：两张图共享了注册表");
    }

    /**
     * 往 {@code writer} 注册一条只属于它的命令，返回在 {@code reader} 上执行该命令的结果。
     *
     * @param writer 注册方
     * @param reader 读取方
     * @return 执行结果；两张图共享注册表时会是一条成功结果
     */
    private static CommandResult registerOnlyIn(JellyfishRuntime writer, JellyfishRuntime reader) {
        writer.extensionRegistry().handle("parity-test", CommandRequest.class, "only-first",
                new CommandDescriptor("只存在于第一张图", null, null, false),
                request -> CommandResult.ok("first"), RegisterOptions.DEFAULT);
        return reader.commandManager().execute("/only-first", null);
    }

    @Test
    void both_strategies_should_share_the_same_TypeRegistry() {
        // Given / When / Then：同步扩展点与事件通道必须落在**同一张**注册表上。
        // 反例的代价很具体：插件停止时的回收（按 owner）只走过通道那一张表，
        // 于是该插件的事件订阅全部残留——插件已经停了、监听器还在被回调的幽灵订阅
        forEachRuntime(assembly -> {
            Subscription subscription = assembly.extensionRegistry().handle("di-parity",
                    CommandRequest.class, "shared-table", new CommandDescriptor("共享表探针", null, null, false),
                    request -> CommandResult.ok("ok"), RegisterOptions.DEFAULT);
            try {
                // 一份表 ⇒ 从通道这一侧按 owner 回收，扩展点那一侧也必然空掉
                assembly.eventChannel().unsubscribeAll("di-parity");
                assertTrue(assembly.extensionRegistry().handlers(CommandRequest.class, "shared-table").isEmpty(),
                        "通道与扩展点各拿一张表：从通道侧按 owner 回收，扩展点上的注册仍在");
            } finally {
                subscription.close();
            }
        });
    }

    @Test
    void pluginContext_should_receive_real_outbound_ports() {
        // Given：插件拿到的那两个出向端口（委派、提问）**必须是真实实现而非占位**。
        // 装成 unavailable() 不会有任何编译期提示，插件照常启动，功能只在运行时悄悄不生效
        JellyfishRuntime assembly = JellyfishAssembler.create(emptyAppConfig());

        // When
        PluginContext context = JellyfishAssembler.pluginContextFactoryOf(assembly)
                .create(PluginDeclaration.of("di-parity"));

        // Then：提问端口就是装配出来的那一个真通道（`attach()` 之前与占位区分不开，因此用同一性断言）
        assertSame(assembly.askChannel(), context.askUser(), "插件拿到的提问端口不是装配的那一个");
        assertNotSame(AskPort.unavailable(), context.askUser(), "提问端口是占位实现");
        assertNotSame(SubAgentPort.unavailable(), context.delegations(), "委派端口是占位实现");
    }

    /**
     * 对两种装配各跑一遍同一段断言：Dagger 组件与手工装配。
     * <p>
     * <b>两者的 {@link AppConfig} 是同一份</b>（此前刻意不同：Dagger 侧固定从 classpath 读、
     * 手工侧由调用方给）。{@code N-07} 之后两侧都由调用方给，因此这里可以也确实应当喂同一份——
     * 「同一段行为对两种装配各跑一遍」只有在输入也相同的时候才说明问题。
     *
     * @param assertions 针对单个装配结果的断言
     */
    private static void forEachRuntime(Consumer<JellyfishRuntime> assertions) {
        AppConfig appConfig = emptyAppConfig();
        List<JellyfishRuntime> assemblies = new ArrayList<JellyfishRuntime>();
        assemblies.add(DaggerJellyfishComponent.builder().appConfig(appConfig).build());
        assemblies.add(JellyfishAssembler.create(appConfig));
        for (JellyfishRuntime assembly : assemblies) {
            assertions.accept(assembly);
        }
    }

    /**
     * 等一条异步事件，等不到时返回 {@code false} 而不抛异常——中断是测试环境的问题，
     * 断言失败才是被测代码的问题，两者不该混在一条栈里。
     *
     * @param latch 待等待的闩锁
     * @return 等到返回 {@code true}
     */
    private static boolean awaitQuietly(CountDownLatch latch) {
        try {
            return latch.await(EVENT_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 构造一份「什么都不配」的应用级配置：所有配置源都是空路径。
     * <p>
     * 内核的纪律是「配置缺失只告警、不中断启动」，因此这是一份合法输入；
     * 用它做装配测试可以不碰任何真实文件，符合「单测不访问外部资源」。
     *
     * @return 应用级配置，保证非 {@code null}
     */
    private static AppConfig emptyAppConfig() {
        return new AppConfig("jellyfish-di-test", new ConfigPaths(), new ConfigPaths(), new ConfigPaths(),
                new PluginPaths(null));
    }
}
