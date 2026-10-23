package io.github.zcd0831.jellyfish.application.memory.port;

/**
 * 按 id 读并渲染 SOP。规则出处：技术决策 D01.1（InternalToolHandler 收窄）。
 */
public interface SkillSopProvider {

    String sop(String skillId);
}
