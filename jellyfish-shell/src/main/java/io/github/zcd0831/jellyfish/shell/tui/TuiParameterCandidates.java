package io.github.zcd0831.jellyfish.shell.tui;

import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.perception.ManageSessionUseCase;
import io.github.zcd0831.jellyfish.application.perception.port.SessionSummary;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.domain.action.model.Model;
import io.github.zcd0831.jellyfish.domain.perception.policy.RuntimePolicy;
import org.jline.reader.Candidate;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * `/open <会话id>`、`/model <模型id>`、`/policy <键>=` 的参数候选。
 * 候选都来自只读列表（会话列表、模型目录、策略字段名），不做动态查询。
 */
final class TuiParameterCandidates implements ParameterCandidates {

    private final ManageSessionUseCase sessions;
    private final CatalogRepository catalog;

    TuiParameterCandidates(ManageSessionUseCase sessions, CatalogRepository catalog) {
        this.sessions = sessions;
        this.catalog = catalog;
    }

    @Override
    public List<Candidate> candidates(String command) {
        if ("/open".equals(command)) {
            return sessionIds();
        }
        if ("/model".equals(command)) {
            return modelIds();
        }
        if ("/policy".equals(command)) {
            return policyKeys();
        }
        return Collections.emptyList();
    }

    private List<Candidate> sessionIds() {
        Result<List<SessionSummary>> listed = sessions.list();
        if (!listed.isOk()) {
            return Collections.emptyList();
        }
        List<Candidate> candidates = new ArrayList<Candidate>();
        for (SessionSummary summary : listed.value()) {
            candidates.add(new Candidate(summary.id().getValue(), summary.id().getValue(),
                    null, summary.title(), null, null, true));
        }
        return candidates;
    }

    private List<Candidate> modelIds() {
        List<Candidate> candidates = new ArrayList<Candidate>();
        for (Model model : catalog.models()) {
            candidates.add(new Candidate(model.id().getValue(), model.id().getValue(),
                    null, model.modelName(), null, null, true));
        }
        return candidates;
    }

    /**
     * 策略键名直接用 `RuntimePolicy` 的字段名：改字段就是改配置键，不需要再养一份清单。
     * 这里只读字段名（不读值），也不碰领域行为。
     */
    private List<Candidate> policyKeys() {
        List<Candidate> candidates = new ArrayList<Candidate>();
        for (Field field : RuntimePolicy.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            String key = field.getName();
            candidates.add(new Candidate(key + "=", key + "=", null, key, null, null, true));
        }
        return candidates;
    }
}
