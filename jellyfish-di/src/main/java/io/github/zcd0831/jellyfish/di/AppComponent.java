package io.github.zcd0831.jellyfish.di;

import dagger.Component;
import io.github.zcd0831.jellyfish.application.action.BudgetService;
import io.github.zcd0831.jellyfish.application.action.ToolExecutor;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
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

import javax.inject.Singleton;

/**
 * 装配根。S1 暴露已实现的端口与用例；记忆模块（经验/反思/SOP）随 S4/S5 加入，
 * 所以本版本没有 MemoryModule——不是遗漏，而是那一层还没有实现。
 */
@Singleton
@Component(modules = {InfrastructureModule.class, PerceptionModule.class, ActionModule.class,
        PlanningModule.class})
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

    /** 供 boot 的关闭钩子取消在途请求（M14）。 */
    HttpJsonClient httpJsonClient();

    SystemPromptAssembler systemPromptAssembler();

    BudgetService budgetService();

    ToolExecutor toolExecutor();

    StartSessionUseCase startSessionUseCase();

    RunSessionUseCase runSessionUseCase();
}
