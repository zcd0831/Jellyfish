package io.github.zcd0831.jellyfish.domain.action.budget;

import io.github.zcd0831.jellyfish.domain.shared.BudgetId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
/** 会话族 token 台账。规则出处：模块-行动 §7.5。 */
public class Budget {

    private BudgetId id;
    private SessionId sessionFamilyRootId;
    private long consumedTokens;
    private boolean warned;
}
