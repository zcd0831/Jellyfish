package io.github.zcd0831.jellyfish.application.perception;

import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.perception.port.RecordStore;
import io.github.zcd0831.jellyfish.application.perception.port.SessionRepository;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.application.shared.UseCaseError;
import io.github.zcd0831.jellyfish.application.shared.port.CommitScope;
import io.github.zcd0831.jellyfish.application.shared.port.DomainEventPublisher;
import io.github.zcd0831.jellyfish.domain.action.model.Model;
import io.github.zcd0831.jellyfish.domain.action.model.ModelError;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;
import io.github.zcd0831.jellyfish.domain.perception.session.SessionError;
import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.util.ArrayList;

/**
 * 用户在会话内显式切换模型（含跨厂商）。
 * 规则出处：04-perception §4.8.1 规则 2、05-action §5.8.3 规则 6、08-application §8.5。
 * <p>唯一硬闸：**工具轮必须已闭合**。模型私有状态（DeepSeek 的 reasoning、Anthropic 的签名
 * thinking、Gemini 的 thoughtSignature）无法跨模型续接，轮中间切换会让下一轮请求不合法。
 * 切换本身只改会话头与派发事件，不动流水；旧 reasoning 由 `producerModelId` 在回放时自动失效。
 */
public final class SwitchModelUseCase {

    private final SessionRepository sessions;
    private final RecordStore records;
    private final CatalogRepository catalog;
    private final CommitScope commitScope;
    private final DomainEventPublisher eventPublisher;

    public SwitchModelUseCase(SessionRepository sessions, RecordStore records, CatalogRepository catalog,
                              CommitScope commitScope, DomainEventPublisher eventPublisher) {
        this.sessions = sessions;
        this.records = records;
        this.catalog = catalog;
        this.commitScope = commitScope;
        this.eventPublisher = eventPublisher;
    }

    public Result<Void> switchTo(SessionId sessionId, ModelId modelId) {
        Session session = sessions.load(sessionId);
        if (session == null) {
            return Result.fail(UseCaseError.of(SessionError.NOT_FOUND, "会话不存在", sessionId));
        }
        Model target = findModel(modelId);
        if (target == null) {
            return Result.fail(UseCaseError.of(ModelError.MODEL_NOT_FOUND, "模型不存在：" + modelId.getValue(),
                    sessionId));
        }
        if (!target.isCallable()) {
            return Result.fail(UseCaseError.of(ModelError.UNAVAILABLE,
                    "模型不可调用：" + modelId.getValue(), sessionId));
        }
        session.hydrateRecords(records.last(sessionId, Integer.MAX_VALUE));
        if (session.hasOpenToolRound()) {
            return Result.fail(UseCaseError.of(SessionError.TOOL_ROUND_OPEN,
                    "工具轮未闭合，等本轮收尾后再切换模型", sessionId));
        }
        if (modelId.equals(session.activeModelId())) {
            return Result.ok(null);
        }
        session.switchModel(modelId);
        commitScope.stageReplace(session);
        commitScope.commit();
        eventPublisher.publishAfterCommit(new ArrayList<DomainEvent>(session.pullEvents()));
        return Result.ok(null);
    }

    private Model findModel(ModelId modelId) {
        for (Model model : catalog.models()) {
            if (model.id().equals(modelId)) {
                return model;
            }
        }
        return null;
    }
}
