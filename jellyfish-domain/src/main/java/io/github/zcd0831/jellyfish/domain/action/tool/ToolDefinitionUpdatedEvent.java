package io.github.zcd0831.jellyfish.domain.action.tool;

import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;

/** 规则出处：05-action §5.4（不变量③）、§5.7。 */
public final class ToolDefinitionUpdatedEvent implements DomainEvent {

    @Override
    public String name() {
        return "tool.definition-updated";
    }
}
