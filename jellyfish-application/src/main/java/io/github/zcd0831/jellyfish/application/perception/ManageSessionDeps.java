package io.github.zcd0831.jellyfish.application.perception;

import io.github.zcd0831.jellyfish.application.action.port.BudgetRepository;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.planning.SessionCancellations;
import io.github.zcd0831.jellyfish.application.shared.port.ArtifactStore;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;

/**
 * {@link ManageSessionUseCase} 的协作者集合：取消 / 归档 / 删除要碰的那些端口，具名装配。
 * 规则出处：08-application §8.9.4、§8.11。
 */
public final class ManageSessionDeps {

    final SessionRepository sessions;
    final ArtifactStore artifacts;
    final CommitScope commitScope;
    final DomainEventPublisher eventPublisher;
    final RecordStore records;
    final ContextWindowService contextWindow;
    final BudgetRepository budgets;
    final PolicyRepository policies;
    final SessionCancellations cancellations;

    private ManageSessionDeps(Builder builder) {
        this.sessions = builder.sessions;
        this.artifacts = builder.artifacts;
        this.commitScope = builder.commitScope;
        this.eventPublisher = builder.eventPublisher;
        this.records = builder.records;
        this.contextWindow = builder.contextWindow;
        this.budgets = builder.budgets;
        this.policies = builder.policies;
        this.cancellations = builder.cancellations;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 具名装配：每个依赖一个 setter，缺哪个构建时就报出来。 */
    public static final class Builder {

        private SessionRepository sessions;
        private ArtifactStore artifacts;
        private CommitScope commitScope;
        private DomainEventPublisher eventPublisher;
        private RecordStore records;
        private ContextWindowService contextWindow;
        private BudgetRepository budgets;
        private PolicyRepository policies;
        private SessionCancellations cancellations;

        public Builder sessions(SessionRepository value) {
            this.sessions = value;
            return this;
        }

        public Builder artifacts(ArtifactStore value) {
            this.artifacts = value;
            return this;
        }

        public Builder commitScope(CommitScope value) {
            this.commitScope = value;
            return this;
        }

        public Builder eventPublisher(DomainEventPublisher value) {
            this.eventPublisher = value;
            return this;
        }

        public Builder records(RecordStore value) {
            this.records = value;
            return this;
        }

        public Builder contextWindow(ContextWindowService value) {
            this.contextWindow = value;
            return this;
        }

        public Builder budgets(BudgetRepository value) {
            this.budgets = value;
            return this;
        }

        public Builder policies(PolicyRepository value) {
            this.policies = value;
            return this;
        }

        public Builder cancellations(SessionCancellations value) {
            this.cancellations = value;
            return this;
        }

        public ManageSessionDeps build() {
            require(sessions, "sessions");
            require(artifacts, "artifacts");
            require(commitScope, "commitScope");
            require(eventPublisher, "eventPublisher");
            require(records, "records");
            require(contextWindow, "contextWindow");
            require(budgets, "budgets");
            require(policies, "policies");
            require(cancellations, "cancellations");
            return new ManageSessionDeps(this);
        }

        private static void require(Object value, String name) {
            if (value == null) {
                throw new IllegalStateException("会话管理缺少协作者：" + name);
            }
        }
    }
}
