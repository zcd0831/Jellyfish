package zcd.jellyfish.plugin.shell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CommandPolicy} 的单元测试。
 * <p>
 * 这里断言的是<b>三类判定</b>（只读不打扰、灾难形状拒绝、其余问人）与白名单的默认拒绝语义。
 * 特别要把「看起来无害但其实是误判点」的那几条钉住：{@code find}、{@code git fetch}、
 * {@code npm test} —— 它们一旦被判成只读，这个机制就从「减少打扰」变成「假装安全」。
 *
 * @author zcd
 */
@DisplayName("CommandPolicy")
class CommandPolicyTest {

    @Test
    @DisplayName("默认策略：只读命令无异议——不打扰人正是它存在的理由")
    void classify_should_mark_readOnlyCommands() {
        CommandPolicy policy = policy(null, Collections.<String>emptyList());

        for (String command : Arrays.asList("ls -la", "cat a.txt", "pwd", "echo hi", "wc -l a.txt",
                "git status", "git diff HEAD~1", "git log --oneline -5")) {
            assertEquals(CommandPolicy.Classification.READ_ONLY, policy.classify(command), command);
        }
    }

    @ParameterizedTest
    @DisplayName("看起来无害的误判点必须落到「问人」那一类")
    @ValueSource(strings = {
            "find . -name '*.tmp' -delete",
            "git fetch origin",
            "git push",
            "npm test",
            "mvn test",
            "docker compose up -d",
            "curl https://example.com -o out.bin",
            "sed -i 's/a/b/' file.txt"})
    void classify_should_not_treat_riskyCommandsAsReadOnly(String command) {
        CommandPolicy policy = policy(null, Collections.<String>emptyList());

        assertEquals(CommandPolicy.Classification.OTHER, policy.classify(command), command);
    }

    @Test
    @DisplayName("灾难形状直接拒绝")
    void classify_should_deny_disasterShapes() {
        CommandPolicy policy = policy(null, Collections.<String>emptyList());

        assertEquals(CommandPolicy.Classification.DENIED, policy.classify("rm -rf /"));
        assertEquals(CommandPolicy.Classification.DENIED, policy.classify("sudo mkfs.ext4 /dev/sda1"));
        assertEquals(CommandPolicy.Classification.DENIED, policy.classify("dd if=/dev/zero of=/dev/sda"));
    }

    @Test
    @DisplayName("只读命令给出无异议，写类命令升级为审批")
    void verdict_should_mapClassification() {
        CommandPolicy policy = policy(null, Collections.<String>emptyList());

        assertTrue(policy.verdict("git status").isAbstain());
        assertTrue(policy.verdict("rm -rf build").isAsk());
        assertTrue(policy.verdict("rm -rf /").isDenied());
    }

    @Test
    @DisplayName("白名单非空即默认拒绝，且不受分类器开关影响")
    void verdict_should_defaultDeny_when_allowListConfigured() {
        Map<String, Object> raw = new HashMap<String, Object>();
        raw.put("enabled", Boolean.FALSE);
        CommandPolicy policy = policy(raw, Arrays.asList("git status", "ls"));

        assertTrue(policy.verdict("git status").isAsk() || policy.verdict("git status").isAbstain());
        assertTrue(policy.verdict("curl https://example.com").isDenied());
        // 关掉的是分类器（便利机制），不是用户明确声明的约束
        assertTrue(policy.verdict("rm -rf build").isDenied());
    }

    @Test
    @DisplayName("白名单支持单 token 与两 token 两种粒度")
    void verdict_should_supportPrefixGranularity() {
        CommandPolicy policy = policy(null, Arrays.asList("npm", "git status"));

        // 单 token 条目覆盖该命令的全部子命令
        assertTrue(policy.verdict("npm install").isAsk());
        // 两 token 条目只覆盖那一个前缀
        assertTrue(policy.verdict("git status").isAbstain());
        assertTrue(policy.verdict("git push").isDenied());
    }

    @Test
    @DisplayName("关掉分类器后只读与其余都不表态")
    void verdict_should_abstain_whenDisabled() {
        Map<String, Object> raw = new HashMap<String, Object>();
        raw.put("enabled", "false");
        CommandPolicy policy = policy(raw, Collections.<String>emptyList());

        assertTrue(policy.verdict("git status").isAbstain());
        assertTrue(policy.verdict("curl https://example.com").isAbstain());
        // 拒绝形状仍在：它不是分类器的便利机制，而是明确的禁止
        assertTrue(policy.verdict("mkfs.ext4 /dev/sda").isDenied());
    }

    @Test
    @DisplayName("只读表与拒绝表都可追加")
    void from_should_extendBothTables() {
        Map<String, Object> raw = new HashMap<String, Object>();
        raw.put("readOnlyCommands", Arrays.asList("terraform plan"));
        raw.put("deniedPatterns", Arrays.asList("shutdown -h"));
        CommandPolicy policy = policy(raw, Collections.<String>emptyList());

        assertEquals(CommandPolicy.Classification.READ_ONLY, policy.classify("terraform plan -out=tfplan"));
        assertEquals(CommandPolicy.Classification.DENIED, policy.classify("sudo shutdown -h now"));
        // 内置表仍在（扩展是叠加而不是替换）
        assertEquals(CommandPolicy.Classification.READ_ONLY, policy.classify("ls"));
        assertEquals(CommandPolicy.Classification.DENIED, policy.classify("rm -rf /"));
    }

    @Test
    @DisplayName("环境变量前缀形状刻意落到「问人」——前缀后面的命令可以是任何东西")
    void classify_should_notTreatAssignmentPrefixAsReadOnly() {
        CommandPolicy policy = policy(null, Collections.<String>emptyList());

        assertEquals(CommandPolicy.Classification.OTHER, policy.classify("FOO=bar ls"));
    }

    /**
     * 构造策略。
     *
     * @param raw             命令策略配置段，可为 {@code null}
     * @param allowedCommands 白名单
     * @return 策略
     */
    private static CommandPolicy policy(Map<String, Object> raw, java.util.List<String> allowedCommands) {
        return CommandPolicy.from(null, raw, allowedCommands);
    }
}
