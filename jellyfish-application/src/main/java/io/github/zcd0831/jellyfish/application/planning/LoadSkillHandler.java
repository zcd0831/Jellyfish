package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.action.InternalToolHandler;
import io.github.zcd0831.jellyfish.application.action.ToolExecOutcome;
import io.github.zcd0831.jellyfish.application.memory.port.SkillSopProvider;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;

import java.util.Map;

/**
 * `load_skill`：取 SOP 文本；**注入成 SYSTEM 记录是内循环的职责**（记忆模块不写别人的会话）。
 * 规则出处：模块-记忆 §8.4 要点、模块-行动 §3.1。
 */
public final class LoadSkillHandler implements InternalToolHandler {

    private final SkillSopProvider sopProvider;

    public LoadSkillHandler(SkillSopProvider sopProvider) {
        this.sopProvider = sopProvider;
    }

    @Override
    public ToolExecOutcome handle(Session session, Map<String, Object> args) {
        Object name = args.get("skillName");
        if (name == null || String.valueOf(name).trim().isEmpty()) {
            return ToolExecOutcome.plain("缺少 skillName");
        }
        return ToolExecOutcome.plain(sopProvider.sop(String.valueOf(name)));
    }
}
