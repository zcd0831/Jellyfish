package io.github.zcd0831.jellyfish.domain.perception.session;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯组装：不查任何定义，内容由调用方取好传入。
 * 规则出处：04-perception §4.5、§4.9.3。
 */
public final class SystemPromptAssembler {

    public SystemPromptSnapshot assemble(AgentSnapshotInput input) {
        String renderedPrompt = input.getBasePrompt() == null
                ? ""
                : input.getBasePrompt().render(input.getPromptVariables());
        return new SystemPromptSnapshot(renderedPrompt, input.getActiveTools(), input.getActiveSkills());
    }

    /**
     * SOP 引用了未被绑定的工具：提示词一致性问题，**告警不阻断**（04-perception §4.9.3）。
     * 领域不持有 IO，所以只给出清单，由调用方决定怎么展示。
     */
    public List<String> unboundToolReferences(AgentSnapshotInput input) {
        List<String> unbound = new ArrayList<>();
        for (String referenced : input.getSopReferencedToolNames()) {
            boolean bound = false;
            for (ToolSchemaSnapshot tool : input.getActiveTools()) {
                if (tool.getName().equals(referenced)) {
                    bound = true;
                    break;
                }
            }
            if (!bound) {
                unbound.add(referenced);
            }
        }
        return unbound;
    }
}
