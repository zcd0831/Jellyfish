package io.github.zcd0831.jellyfish.domain.action.budget;

import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;

/** 一族只报一次，不中断。规则出处：模块-行动 §6、§7.5 规则 ④。 */
public final class BudgetWarning implements DomainEvent {

    @Override
    public String name() {
        return "budget.warning";
    }
}
