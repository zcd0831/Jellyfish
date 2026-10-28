package io.github.zcd0831.jellyfish.application.memory;

/**
 * 子代理说的经验（不可信输入）：写入前还要过 {@code Experience} 的规则。
 * 规则出处：07-memory §7.6、§7.9.2。
 */
public final class ExperienceDraft {

    private final String applicableScenario;
    private final String instruction;
    private final String expectedOutcome;

    public ExperienceDraft(String applicableScenario, String instruction, String expectedOutcome) {
        this.applicableScenario = applicableScenario;
        this.instruction = instruction;
        this.expectedOutcome = expectedOutcome;
    }

    public String applicableScenario() {
        return applicableScenario;
    }

    public String instruction() {
        return instruction;
    }

    public String expectedOutcome() {
        return expectedOutcome;
    }
}
