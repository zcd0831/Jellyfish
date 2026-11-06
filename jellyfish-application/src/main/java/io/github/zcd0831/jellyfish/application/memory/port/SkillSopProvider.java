package io.github.zcd0831.jellyfish.application.memory.port;

import java.util.Optional;

/**
 * 按 id 读并渲染 SOP：**取不到就返回空**，不返回"不可用"这类说明文字——
 * 否则"真的拿到了流程正文"与"技能不存在"在类型上分不开，
 * 内循环会把失败的加载也算成"这次用过这个技能"（改进判定的证据就脏了）。
 * 规则出处：07-memory §7.9.4。
 */
public interface SkillSopProvider {

    Optional<String> sop(String skillId);
}
