package zcd.jellyfish.di;

import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.event.Subscription;
import zcd.jellyfish.api.event.notification.SessionClosedEvent;
import zcd.jellyfish.api.extension.CommandDescriptor;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.SessionBeforeCloseRequest;
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
    void accessors_should_all_be_present_when_assembled() {
        // Given / When / Then：20 个访问器一个都不能是 null，否则外壳会在第一次用到时才发现
        forEachRuntime(assembly -> {
            assertNotNull(assembly.runtimeConfig(), "runtimeConfig");
            assertNotNull(assembly.agentHarness(), "agentHarness");
            assertNotNull(assembly.agentManager(), "agentManager");
            assertNotNull(assembly.modelManager(), "modelManager");
            assertNotNull(assembly.llmClientFactory(), "llmClientFactory");
            assertNotNull(assembly.permissionManager(), "permissionManager");
            assertNotNull(assembly.approvalChannel(), "approvalChannel");
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
        String report = JellyfishAssembler.create(emptyAppConfig()).healthCheck().check().render();

        // Then
        assertNotNull(report);
        assertFalse(report.trim().isEmpty(), "健康报告不应为空");
    }

    @Test
    void create_should_return_independent_graphs_when_called_twice() {
        // Given：两次装配必须是两张图，不能靠静态状态串起来
        JellyfishRuntime first = JellyfishAssembler.create(emptyAppConfig());
        JellyfishRuntime second = JellyfishAssembler.create(emptyAppConfig());

        // When：只往第一张图里注册
        first.extensionRegistry().handle("parity-test", CommandRequest.class, "only-first",
                new CommandDescriptor("只存在于第一张图", null, null, false),
                request -> CommandResult.ok("first"), RegisterOptions.DEFAULT);

        // Then：第二张图看不见它，说明注册表没有跨图共享
        assertTrue(second.commandManager().execute("/only-first", null).isError());
    }

    /**
     * 对两种装配各跑一遍同一段断言：Dagger 组件与手工装配。
     * <p>
     * 两者的 {@link AppConfig} 不同是刻意的——Dagger 组件从 classpath 读（测试 classpath 上没有
     * {@code config.json}，于是退化为缺省配置），手工装配则显式给一个空配置。这一点差异不影响
     * 这里要验的装配结构。
     *
     * @param assertions 针对单个装配结果的断言
     */
    private static void forEachRuntime(Consumer<JellyfishRuntime> assertions) {
        List<JellyfishRuntime> assemblies = new ArrayList<JellyfishRuntime>();
        assemblies.add(DaggerJellyfishComponent.create());
        assemblies.add(JellyfishAssembler.create(emptyAppConfig()));
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
