package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.action.SessionToolScope;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.memory.ExperienceFeedbackService;
import io.github.zcd0831.jellyfish.application.memory.PlanCompletionCurator;
import io.github.zcd0831.jellyfish.application.memory.port.ExperienceRepository;
import io.github.zcd0831.jellyfish.application.memory.port.ReflectionRepository;
import io.github.zcd0831.jellyfish.application.perception.InternalSessionRunner;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.application.shared.port.CancellationRegistry;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.domain.planning.plan.PlanFactory;

import java.util.Optional;

/**
 * `ExecutionLoop` 的协作者：15 个位置参数换成具名装配，缺一个就在 `build()` 当场失败。
 * <p>位置参数过了 5 个就没人能凭肉眼校验顺序了（`plans`/`planFactory`、`reflections`/`experiences`
 * 这种成对的尤其危险）；这也和 `StartSessionDeps` / `ManageSessionDeps` 保持同一风格。
 * 规则出处：08-application §8.4（应用组件的协作对象）、08-application §8.11。
 */
public final class ExecutionLoopDeps {

    private ReActLoop reActLoop;
    private VerificationRunner verificationRunner;
    private PlanRepository plans;
    private PlanFactory planFactory;
    private CatalogRepository catalog;
    private PolicyRepository policies;
    private CommitScope commitScope;
    private DomainEventPublisher eventPublisher;
    private ProgressPort progress;
    private CancellationRegistry cancellations;
    private ExperienceFeedbackService experienceFeedback;
    private PlanCompletionCurator planCompletionCurator;
    private ReflectionRepository reflections;
    private ExperienceRepository experiences;
    private SessionToolScope scope;
    private InternalSessionRunner forks;

    private ExecutionLoopDeps() {
    }

    public static ExecutionLoopDeps builder() {
        return new ExecutionLoopDeps();
    }

    public ExecutionLoopDeps reActLoop(ReActLoop value) {
        this.reActLoop = value;
        return this;
    }

    public ExecutionLoopDeps verificationRunner(VerificationRunner value) {
        this.verificationRunner = value;
        return this;
    }

    public ExecutionLoopDeps plans(PlanRepository value) {
        this.plans = value;
        return this;
    }

    public ExecutionLoopDeps planFactory(PlanFactory value) {
        this.planFactory = value;
        return this;
    }

    public ExecutionLoopDeps catalog(CatalogRepository value) {
        this.catalog = value;
        return this;
    }

    public ExecutionLoopDeps policies(PolicyRepository value) {
        this.policies = value;
        return this;
    }

    public ExecutionLoopDeps commitScope(CommitScope value) {
        this.commitScope = value;
        return this;
    }

    public ExecutionLoopDeps eventPublisher(DomainEventPublisher value) {
        this.eventPublisher = value;
        return this;
    }

    public ExecutionLoopDeps progress(ProgressPort value) {
        this.progress = value;
        return this;
    }

    public ExecutionLoopDeps cancellations(CancellationRegistry value) {
        this.cancellations = value;
        return this;
    }

    public ExecutionLoopDeps experienceFeedback(ExperienceFeedbackService value) {
        this.experienceFeedback = value;
        return this;
    }

    public ExecutionLoopDeps planCompletionCurator(PlanCompletionCurator value) {
        this.planCompletionCurator = value;
        return this;
    }

    public ExecutionLoopDeps reflections(ReflectionRepository value) {
        this.reflections = value;
        return this;
    }

    public ExecutionLoopDeps experiences(ExperienceRepository value) {
        this.experiences = value;
        return this;
    }

    public ExecutionLoopDeps scope(SessionToolScope value) {
        this.scope = value;
        return this;
    }

    public ExecutionLoopDeps forks(InternalSessionRunner value) {
        this.forks = value;
        return this;
    }

    /** 缺任何一项都当场失败：装配错误要死在启动，不要死在第一次跑会话。 */
    public ExecutionLoop build() {
        return new ExecutionLoop(this);
    }

    <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalStateException("ExecutionLoop 装配缺失：" + name);
        }
        return value;
    }

    ReActLoop reActLoop() {
        return reActLoop;
    }

    VerificationRunner verificationRunner() {
        return verificationRunner;
    }

    PlanRepository plans() {
        return plans;
    }

    PlanFactory planFactory() {
        return planFactory;
    }

    CatalogRepository catalog() {
        return catalog;
    }

    PolicyRepository policies() {
        return policies;
    }

    CommitScope commitScope() {
        return commitScope;
    }

    DomainEventPublisher eventPublisher() {
        return eventPublisher;
    }

    ProgressPort progress() {
        return progress;
    }

    CancellationRegistry cancellations() {
        return cancellations;
    }

    ExperienceFeedbackService experienceFeedback() {
        return experienceFeedback;
    }

    PlanCompletionCurator planCompletionCurator() {
        return planCompletionCurator;
    }

    ReflectionRepository reflections() {
        return reflections;
    }

    ExperienceRepository experiences() {
        return experiences;
    }

    SessionToolScope scope() {
        return scope;
    }

    InternalSessionRunner forks() {
        return forks;
    }
}
