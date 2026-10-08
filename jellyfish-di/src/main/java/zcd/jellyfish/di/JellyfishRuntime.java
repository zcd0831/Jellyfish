package zcd.jellyfish.di;

import zcd.jellyfish.core.AgentHarness;
import zcd.jellyfish.core.compact.ConversationCompactor;
import zcd.jellyfish.core.conversation.ConversationService;
import zcd.jellyfish.core.conversation.ShellStreams;
import zcd.jellyfish.core.conversation.TurnRegistry;
import zcd.jellyfish.core.input.InputDirectives;
import zcd.jellyfish.core.runtime.RunEventBus;
import zcd.jellyfish.infra.agent.AgentManager;
import zcd.jellyfish.infra.ask.AskChannel;
import zcd.jellyfish.infra.command.CommandManager;
import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.config.AppConfig;
import zcd.jellyfish.infra.config.ProjectConfigTrust;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.llm.LlmClientFactory;
import zcd.jellyfish.infra.metrics.HealthCheck;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.permission.ApprovalChannel;
import zcd.jellyfish.infra.permission.PermissionManager;
import zcd.jellyfish.infra.plugin.RuntimeInfoHolder;
import zcd.jellyfish.infra.session.SessionDefaults;
import zcd.jellyfish.infra.session.SessionManager;

/**
 * 内核运行时门面：装配完成后的对象图向外交付的那一组入口。
 * <p>
 * <b>为什么要有这个接口</b>：装配知识此前只以 Dagger2 组件的形式存在（{@link JellyfishComponent}），
 * 而 Dagger 的实现类是注解处理器生成的——任何想复用内核的外壳都必须先依赖 Dagger 代码生成。
 * 把入口抽成一个<b>不含任何 DI 注解的普通接口</b>之后，装配方式与交付形态解耦：
 * 外壳只认这 21 个访问器，至于对象图是 Dagger 生成的还是手写的，它不关心。
 * <p>
 * <b>本接口刻意不声明生命周期</b>：启动与收敛是 {@link AgentHarness#bootstrap()} /
 * {@link AgentHarness#shutdown()} 的事，外壳按自己的时机调（CLI 在 {@code Launcher} 里，
 * Spring 外壳在 {@code SmartLifecycle} 里）。接口里再放一对 start/stop 只会多出「谁先谁后」的
 * 第二处说法，而启动顺序是内核的硬约束，只能有一处。
 * <p>
 * <b>实现者必须保证「同一实例」约束</b>：例如 {@code EventPublisher} 与 {@link #eventChannel()}
 * 必须是同一个对象、{@link ExtensionRegistry} 与 {@link EventChannel} 必须共用同一份
 * {@code TypeRegistry}。这些约束不体现在方法签名上，只能靠各实现自己守住；两套实现的一致性
 * 由 {@code JellyfishAssemblerTest} 断言。
 *
 * @author zcd
 */
public interface JellyfishRuntime {

    /**
     * 获取 LLM 客户端工厂。
     *
     * @return LLM 客户端工厂，保证非 {@code null}
     */
    LlmClientFactory llmClientFactory();

    /**
     * 获取模型管理器。
     *
     * @return 模型管理器，保证非 {@code null}
     */
    ModelManager modelManager();

    /**
     * 获取运行时配置门面。
     *
     * @return 运行时配置门面，保证非 {@code null}
     */
    RuntimeConfig runtimeConfig();

    /**
     * 获取应用级配置（各配置段的双源路径与插件扫描目录）。
     * <p>
     * 外壳在启动期需要它来算出「哪些项目级配置文件存在」，以便决定要不要向用户征求信任。
     *
     * @return 应用级配置，保证非 {@code null}
     */
    AppConfig appConfig();

    /**
     * 获取项目级配置的信任裁决。
     * <p>
     * 外壳在 {@code bootstrap()} 之前用它授予信任（启动参数或交互确认），
     * {@code RuntimeConfig} 在 {@code refresh()} 里用它决定项目级配置是否参与合并。
     *
     * @return 信任裁决，保证非 {@code null}
     */
    ProjectConfigTrust projectConfigTrust();

    /**
     * 获取 agent 运行时宿主。
     *
     * @return agent 运行时宿主，保证非 {@code null}
     */
    AgentHarness agentHarness();

    /**
     * 获取 agent 门面。
     *
     * @return agent 门面，保证非 {@code null}
     */
    AgentManager agentManager();

    /**
     * 获取权限判定器。
     *
     * @return 权限判定器，保证非 {@code null}
     */
    PermissionManager permissionManager();

    /**
     * 获取人工审批通道。
     *
     * @return 人工审批通道，保证非 {@code null}
     */
    ApprovalChannel approvalChannel();

    /**
     * 获取向用户提问通道。
     *
     * @return 向用户提问通道，保证非 {@code null}
     */
    AskChannel askChannel();

    /**
     * 获取会话压缩器。
     *
     * @return 会话压缩器，保证非 {@code null}
     */
    ConversationCompactor conversationCompactor();

    /**
     * 获取输入指令服务。
     *
     * @return 输入指令服务，保证非 {@code null}
     */
    InputDirectives inputDirectives();

    /**
     * 获取命令域服务。
     *
     * @return 命令域服务，保证非 {@code null}
     */
    CommandManager commandManager();

    /**
     * 获取会话域服务。
     *
     * @return 会话域服务，保证非 {@code null}
     */
    SessionManager sessionManager();

    /**
     * 取本进程内新建会话的待生效默认值。
     *
     * @return 会话默认值，保证非 {@code null}
     */
    SessionDefaults sessionDefaults();

    /**
     * 获取同步扩展点策略。
     *
     * @return 同步扩展点策略，保证非 {@code null}
     */
    ExtensionRegistry extensionRegistry();

    /**
     * 获取事件通道。
     *
     * @return 事件通道，保证非 {@code null}
     */
    EventChannel eventChannel();

    /**
     * 获取运行时信息持有者。
     *
     * @return 运行时信息持有者，保证非 {@code null}
     */
    RuntimeInfoHolder runtimeInfoHolder();

    /**
     * 获取会话提交服务。
     *
     * @return 会话提交服务，保证非 {@code null}
     */
    ConversationService conversationService();

    /**
     * 获取在途回合表。
     *
     * @return 在途回合表，保证非 {@code null}
     */
    TurnRegistry turnRegistry();

    /**
     * 获取外壳通道门面。
     *
     * @return 外壳通道门面，保证非 {@code null}
     */
    ShellStreams shellStreams();

    /**
     * 获取 agent run 事件总线。
     *
     * @return agent run 事件总线，保证非 {@code null}
     */
    RunEventBus runEventBus();

    /**
     * 获取健康检查汇总。
     *
     * @return 健康检查汇总，保证非 {@code null}
     */
    HealthCheck healthCheck();
}
