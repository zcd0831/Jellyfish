package io.github.zcd0831.jellyfish.domain.memory.experience;

import io.github.zcd0831.jellyfish.domain.shared.DomainEvent;
import io.github.zcd0831.jellyfish.domain.shared.ExperienceId;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局经验库里的一条经验：适用场景 + 教训 + 置信度 + 使用统计。
 * 语义不绑定任何维度（不绑 Agent / 工具 / 技能）。
 * 规则出处：模块-记忆 §3.1、§7.1。
 */
public class Experience {

    private final ExperienceId id;
    private final long createdAt;
    private final List<DomainEvent> pendingEvents = new ArrayList<DomainEvent>();

    private String applicableScenario;
    private LessonLearned lesson;
    private ConfidenceScore confidence;
    private ExperienceStatus status;
    private int appliedCount;

    public Experience(ExperienceId id, String applicableScenario, LessonLearned lesson,
                      ConfidenceScore confidence, ExperienceStatus status, int appliedCount, long createdAt) {
        this.id = id;
        this.applicableScenario = applicableScenario;
        this.lesson = lesson;
        this.confidence = confidence;
        this.status = status;
        this.appliedCount = appliedCount;
        this.createdAt = createdAt;
    }

    /** 规则出处：§7.1 规则 ⑤（初值由策略给定）。 */
    public static Experience create(ExperienceId id, String scenario, LessonLearned lesson,
                                    double initialConfidence, long now) {
        Experience experience = new Experience(id, scenario, lesson,
                new ConfidenceScore(initialConfidence), ExperienceStatus.ACTIVE, 0, now);
        experience.pendingEvents.add(new ExperienceCreated());
        return experience;
    }

    /** 成功只奖励"被建议的"经验（§7.1 规则 ⑧）。步长来自策略，聚合不持有它。 */
    public void reinforce(double step) {
        this.confidence = new ConfidenceScore(Math.min(1.0, confidence.getValue() + step));
        this.appliedCount++;
        pendingEvents.add(new ExperienceReinforced());
    }

    /** 失败惩罚"本次注入的全部"，跌破阈值即淘汰（§7.1 规则 ⑤⑧）。 */
    public void penalize(double step, double retireThreshold) {
        this.confidence = new ConfidenceScore(Math.max(0.0, confidence.getValue() - step));
        if (confidence.getValue() < retireThreshold) {
            this.status = ExperienceStatus.ARCHIVED;
        }
        pendingEvents.add(new ExperiencePenalized());
    }

    /** 语义重复：保留更清晰的表述、合并使用统计、提置信度（§7.1 规则 ④⑨）。 */
    public void mergeWith(Experience other, double step) {
        if (other.lesson != null && this.lesson == null) {
            this.lesson = other.lesson;
        }
        this.appliedCount += other.appliedCount;
        // 合并只提置信度，不再额外计一次"被应用"——使用次数来自两条经验的实际累加
        this.confidence = new ConfidenceScore(Math.min(1.0, confidence.getValue() + step));
        pendingEvents.add(new ExperienceReinforced());
    }

    /** ARCHIVED 后不再参与检索，但保留供审计（§7.1）。 */
    public void archive() {
        this.status = ExperienceStatus.ARCHIVED;
    }

    public boolean isActive() {
        return status == ExperienceStatus.ACTIVE;
    }

    public List<DomainEvent> pullEvents() {
        List<DomainEvent> events = new ArrayList<DomainEvent>(pendingEvents);
        pendingEvents.clear();
        return events;
    }

    public ExperienceId id() {
        return id;
    }

    public String applicableScenario() {
        return applicableScenario;
    }

    public LessonLearned lesson() {
        return lesson;
    }

    public ConfidenceScore confidence() {
        return confidence;
    }

    public ExperienceStatus status() {
        return status;
    }

    public int appliedCount() {
        return appliedCount;
    }

    public long createdAt() {
        return createdAt;
    }
}
