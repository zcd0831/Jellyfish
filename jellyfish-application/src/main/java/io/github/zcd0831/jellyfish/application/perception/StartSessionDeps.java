package io.github.zcd0831.jellyfish.application.perception;

import io.github.zcd0831.jellyfish.application.action.port.BudgetRepository;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.planning.SessionCancellations;
import io.github.zcd0831.jellyfish.application.shared.port.Clock;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.domain.perception.session.SystemPromptAssembler;

/**
 * {@link StartSessionUseCase} 的协作者集合：9 个位置参数换成具名装配。
 * 手工装配（测试、boot 验收）不会再因为顺序抄错而"绿得莫名其妙"。
 * 规则出处：08-application §8.9.2（会话创建只此一处）、§8.11（依赖显式可见）。
 */
public final class StartSessionDeps {

    final CatalogRepository catalog;
    final SessionRepository sessions;
    final BudgetRepository budgets;
    final SessionTitleGenerator titleGenerator;
    final SystemPromptAssembler assembler;
    final CommitScope commitScope;
    final Clock clock;
    final SessionCancellations cancellations;
    final DomainEventPublisher eventPublisher;

    private StartSessionDeps(Builder builder) {
        this.catalog = builder.catalog;
        this.sessions = builder.sessions;
        this.budgets = builder.budgets;
        this.titleGenerator = builder.titleGenerator;
        this.assembler = builder.assembler;
        this.commitScope = builder.commitScope;
        this.clock = builder.clock;
        this.cancellations = builder.cancellations;
        this.eventPublisher = builder.eventPublisher;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 具名装配：每个依赖一个 setter，缺哪个构建时就报出来。 */
    public static final class Builder {

        private CatalogRepository catalog;
        private SessionRepository sessions;
        private BudgetRepository budgets;
        private SessionTitleGenerator titleGenerator;
        private SystemPromptAssembler assembler;
        private CommitScope commitScope;
        private Clock clock;
        private SessionCancellations cancellations;
        private DomainEventPublisher eventPublisher;

        public Builder catalog(CatalogRepository value) {
            this.catalog = value;
            return this;
        }

        public Builder sessions(SessionRepository value) {
            this.sessions = value;
            return this;
        }

        public Builder budgets(BudgetRepository value) {
            this.budgets = value;
            return this;
        }

        public Builder titleGenerator(SessionTitleGenerator value) {
            this.titleGenerator = value;
            return this;
        }

        public Builder assembler(SystemPromptAssembler value) {
            this.assembler = value;
            return this;
        }

        public Builder commitScope(CommitScope value) {
            this.commitScope = value;
            return this;
        }

        public Builder clock(Clock value) {
            this.clock = value;
            return this;
        }

        public Builder cancellations(SessionCancellations value) {
            this.cancellations = value;
            return this;
        }

        public Builder eventPublisher(DomainEventPublisher value) {
            this.eventPublisher = value;
            return this;
        }

        public StartSessionDeps build() {
            require(catalog, "catalog");
            require(sessions, "sessions");
            require(budgets, "budgets");
            require(titleGenerator, "titleGenerator");
            require(assembler, "assembler");
            require(commitScope, "commitScope");
            require(clock, "clock");
            require(cancellations, "cancellations");
            require(eventPublisher, "eventPublisher");
            return new StartSessionDeps(this);
        }

        private static void require(Object value, String name) {
            if (value == null) {
                throw new IllegalStateException("会话开启缺少协作者：" + name);
            }
        }
    }
}
