package zcd.jellyfish.plugin.skills;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次扫描的完整结果：可用的 skill、以及逐条目隔离下来的问题。
 * <p>
 * <b>问题与结果一起给，而不是抛异常</b>：一个 skill 的 {@code SKILL.md} 写坏了，不该让其余 skill
 * 一起消失——这与脚本桥接插件「单个脚本问题只记台账」是同一条取舍。问题清单最终渲染在
 * {@code /skills} 台账上，因为那些条目的现场表现恰好是「模型看不见它」。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class SkillScanResult {

    /** 可用的 skill，按发现顺序。 */
    private final List<SkillDefinition> skills;

    /** 扫描期问题，按发现顺序。 */
    private final List<String> issues;

    /**
     * 构造扫描结果。
     *
     * @param skills 可用的 skill，可为 {@code null}
     * @param issues 扫描期问题，可为 {@code null}
     */
    SkillScanResult(List<SkillDefinition> skills, List<String> issues) {
        this.skills = Collections.unmodifiableList(
                skills == null ? new ArrayList<SkillDefinition>() : new ArrayList<SkillDefinition>(skills));
        this.issues = Collections.unmodifiableList(
                issues == null ? new ArrayList<String>() : new ArrayList<String>(issues));
    }

    /**
     * 构造空结果。
     *
     * @return 空结果
     */
    static SkillScanResult empty() {
        return new SkillScanResult(null, null);
    }

    /**
     * 获取可用的 skill。
     *
     * @return 不可变列表，保证非 {@code null}
     */
    List<SkillDefinition> skills() {
        return skills;
    }

    /**
     * 获取扫描期问题。
     *
     * @return 不可变列表，保证非 {@code null}
     */
    List<String> issues() {
        return issues;
    }

    /**
     * 判断是否没有可用的 skill。
     *
     * @return 没有 skill 返回 {@code true}
     */
    boolean isEmpty() {
        return skills.isEmpty();
    }

    /**
     * 按名称查找 skill。
     *
     * @param name 名称，可为 {@code null}
     * @return 命中的 skill；未命中时返回 {@code null}
     */
    SkillDefinition find(String name) {
        if (name == null) {
            return null;
        }
        for (SkillDefinition skill : skills) {
            if (skill.name().equals(name)) {
                return skill;
            }
        }
        return null;
    }

    /**
     * 取全部名称，供「没找到」的错误文案列出可选项。
     *
     * @return 不可变名称列表
     */
    List<String> names() {
        Set<String> names = new LinkedHashSet<String>();
        for (SkillDefinition skill : skills) {
            names.add(skill.name());
        }
        return Collections.unmodifiableList(new ArrayList<String>(names));
    }

    @Override
    public String toString() {
        return "SkillScanResult{skills=" + skills.size() + ", issues=" + issues.size() + '}';
    }
}
