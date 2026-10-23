package io.github.zcd0831.jellyfish.application.action.port;

import io.github.zcd0831.jellyfish.domain.action.budget.Budget;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

/**
 * 会话族台账。规则出处：模块-基础设施层 §3。
 */
public interface BudgetRepository {

    Budget load(SessionId sessionFamilyRootId);

    void save(Budget budget);
}
