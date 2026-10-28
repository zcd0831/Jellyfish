package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.action.ToolInvoker;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.perception.ContextWindowService;
import io.github.zcd0831.jellyfish.application.planning.port.PlanRepository;
import io.github.zcd0831.jellyfish.application.shared.CancellationToken;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.port.HumanInteractionPort;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;

/**
 * 交给 {@link SessionRunner} 的运行上下文：写一个自己的循环所需的全部手脚。
 * 只给"能力"，不给 `CatalogRepository`——工具/技能清单已冻结在会话快照里，
 * 再开一条"实时看世界"的通道会与"声明按会话冻结"打架。
 */
public final class RunContext {

    private final Session session;
    private final ChunkSink sink;
    private final CancellationToken cancellation;
    private final ModelInvocationPort model;
    private final ToolInvoker tools;
    private final HumanInteractionPort human;
    private final ProgressPort progress;
    private final PlanRepository plans;
    private final ContextWindowService contextWindow;

    public RunContext(Session session, ChunkSink sink, CancellationToken cancellation,
                      ModelInvocationPort model, ToolInvoker tools, HumanInteractionPort human,
                      ProgressPort progress, PlanRepository plans, ContextWindowService contextWindow) {
        this.session = session;
        this.sink = sink;
        this.cancellation = cancellation;
        this.model = model;
        this.tools = tools;
        this.human = human;
        this.progress = progress;
        this.plans = plans;
        this.contextWindow = contextWindow;
    }

    public Session session() {
        return session;
    }

    public ChunkSink sink() {
        return sink;
    }

    public CancellationToken cancellation() {
        return cancellation;
    }

    public ModelInvocationPort model() {
        return model;
    }

    public ToolInvoker tools() {
        return tools;
    }

    public HumanInteractionPort human() {
        return human;
    }

    public ProgressPort progress() {
        return progress;
    }

    public PlanRepository plans() {
        return plans;
    }

    public ContextWindowService contextWindow() {
        return contextWindow;
    }
}
