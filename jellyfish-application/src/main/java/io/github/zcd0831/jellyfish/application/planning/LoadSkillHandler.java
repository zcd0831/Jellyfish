package io.github.zcd0831.jellyfish.application.planning;

import io.github.zcd0831.jellyfish.application.action.InternalToolHandler;
import io.github.zcd0831.jellyfish.application.action.ToolExecOutcome;
import io.github.zcd0831.jellyfish.application.memory.port.SkillSopProvider;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;

import java.util.Map;

/**
 * `load_skill`：取 SOP 文本；**注入成 SYSTEM 记录是内循环的职责**（记忆模块不写别人的会话）。
 * 规则出处：07-memory §7.9.4 要点、05-action §5.4.1。
 */
public final class LoadSkillHandler implements InternalToolHandler {

    /** 取不到 SOP 时的结果文案：内循环据此判断"这次没拿到流程正文"。 */
    private static final String UNAVAILABLE = "技能不可用：";

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
        java.util.Optional<String> sop = sopProvider.sop(String.valueOf(name));
        // 取不到就是失败结果：不给模型编一段"技能不可用"当 SOP 注入（07-memory §7.9.4）
        return sop.isPresent() ? ToolExecOutcome.plain(sop.get())
                : ToolExecOutcome.plain(UNAVAILABLE + name);
    }

    /**
     * 这次 `load_skill` 是否拿到了流程正文。结果对象没有成功/失败标志（05-action §5.6「失败也是一种结果」），
     * 所以把判断和产生文案的代码放在一起——判定只认这里产生的这一种文案。
     */
    static boolean unavailable(ToolExecOutcome outcome) {
        return outcome.content() == null || outcome.content().startsWith(UNAVAILABLE);
    }
}
