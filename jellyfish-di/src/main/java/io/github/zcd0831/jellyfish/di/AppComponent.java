package io.github.zcd0831.jellyfish.di;

import dagger.Component;
import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.ToolExecutor;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.action.ManageCatalogUseCase;
import io.github.zcd0831.jellyfish.application.perception.AdjustPolicyUseCase;
import io.github.zcd0831.jellyfish.application.perception.ManageSessionUseCase;
import io.github.zcd0831.jellyfish.application.perception.StartSessionUseCase;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.planning.RunSessionUseCase;
import io.github.zcd0831.jellyfish.application.shared.port.Clock;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.application.shared.port.HumanInteractionPort;
import io.github.zcd0831.jellyfish.application.shared.port.InstanceLock;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.application.shared.port.SchemaMigrator;
import io.github.zcd0831.jellyfish.application.shared.port.TokenCounter;
import io.github.zcd0831.jellyfish.domain.perception.session.SystemPromptAssembler;
import io.github.zcd0831.jellyfish.infrastructure.file.DataDirectory;
import io.github.zcd0831.jellyfish.infrastructure.file.JsonCodec;
import io.github.zcd0831.jellyfish.infrastructure.model.HttpJsonClient;

import java.util.concurrent.ExecutorService;
import javax.inject.Singleton;

/**
 * 装配根：端口 + 用例 + 各认知模块的应用服务。
 */
@Singleton
@Component(modules = {InfrastructureModule.class, PerceptionModule.class, ActionModule.class,
        PlanningModule.class, MemoryModule.class, ShellModule.class})
public interface AppComponent {

    DataDirectory dataDirectory();

    JsonCodec jsonCodec();

    SessionRepository sessionRepository();

    RecordStore recordStore();

    PolicyRepository policyRepository();

    CatalogRepository catalogRepository();

    CommitScope commitScope();

    InstanceLock instanceLock();

    SchemaMigrator schemaMigrator();

    Clock clock();

    DomainEventPublisher domainEventPublisher();

    ProgressPort progressPort();

    HumanInteractionPort humanInteractionPort();

    TokenCounter tokenCounter();

    /** 供 boot 的关闭钩子停止子代理池（D05-3）。 */
    ExecutorService subagentPool();

    /** 供 boot 的关闭钩子取消在途请求（M14）。 */
    HttpJsonClient httpJsonClient();

    SystemPromptAssembler systemPromptAssembler();

    BudgetService budgetService();

    ToolExecutor toolExecutor();

    StartSessionUseCase startSessionUseCase();

    RunSessionUseCase runSessionUseCase();

    ManageSessionUseCase manageSessionUseCase();

    AdjustPolicyUseCase adjustPolicyUseCase();

    ManageCatalogUseCase manageCatalogUseCase();
}
