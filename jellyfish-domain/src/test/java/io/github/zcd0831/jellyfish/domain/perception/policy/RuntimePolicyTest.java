package io.github.zcd0831.jellyfish.domain.perception.policy;

import io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 逐项校验：正数 / 非负 / 比例 0~1 / 列表。规则出处：模块-感知 §7.2、§8.5。 */
class RuntimePolicyTest {

    private static Map<String, Object> one(String key, Object value) {
        Map<String, Object> changes = new LinkedHashMap<String, Object>();
        changes.put(key, value);
        return changes;
    }

    private final RuntimePolicy policy = RuntimePolicy.defaults();

    @Test
    void 未提供的项保持原值() {
        RuntimePolicy adjusted = policy.adjust(Collections.<String, Object>emptyMap());
        assertEquals(policy.maxRetries(), adjusted.maxRetries());
        assertEquals(policy.budgetLimitTokens(), adjusted.budgetLimitTokens());
    }

    @Test
    void 正整数项接受合法值() {
        assertEquals(5, policy.adjust(one("maxRetries", "5")).maxRetries());
        assertEquals(7, policy.adjust(one("reflectionThreshold", 7)).reflectionThreshold());
        assertEquals(9, policy.adjust(one("maxReplanGeneration", 9)).maxReplanGeneration());
        assertEquals(11, policy.adjust(one("maxReActRounds", 11)).maxReActRounds());
        assertEquals(5000, policy.adjust(one("experienceCapacity", 5000)).experienceCapacity());
        assertEquals(3, policy.adjust(one("experienceTopN", 3)).experienceTopN());
        assertEquals(2, policy.adjust(one("experiencePerPlan", 2)).experiencePerPlan());
    }

    @Test
    void 非正数被拒绝且非数字也拒绝() {
        assertThrows(DomainInvariantViolation.class, () -> policy.adjust(one("maxRetries", "0")));
        assertThrows(DomainInvariantViolation.class, () -> policy.adjust(one("maxRetries", "-3")));
        assertThrows(DomainInvariantViolation.class, () -> policy.adjust(one("maxRetries", "abc")));
    }

    @Test
    void 安全余量允许为零但不允许负() {
        assertEquals(0, policy.adjust(one("inputSafetyMargin", 0)).inputSafetyMargin());
        assertThrows(DomainInvariantViolation.class, () -> policy.adjust(one("inputSafetyMargin", -1)));
    }

    @Test
    void 比例类必须落在0到1之间() {
        assertEquals(0.25, policy.adjust(one("systemRecordRatioLimit", "0.25")).systemRecordRatioLimit(), 1e-9);
        assertThrows(DomainInvariantViolation.class, () -> policy.adjust(one("budgetWarnRatio", "1.2")));
        assertThrows(DomainInvariantViolation.class, () -> policy.adjust(one("budgetWarnRatio", "-0.1")));
        assertThrows(DomainInvariantViolation.class, () -> policy.adjust(one("budgetWarnRatio", "abc")));
    }

    @Test
    void 额度为非负长整型() {
        assertEquals(2000000L, policy.adjust(one("budgetLimitTokens", "2000000")).budgetLimitTokens());
        assertEquals(0L, policy.adjust(one("budgetLimitTokens", 0)).budgetLimitTokens());
        assertThrows(DomainInvariantViolation.class, () -> policy.adjust(one("budgetLimitTokens", "-1")));
        assertThrows(DomainInvariantViolation.class, () -> policy.adjust(one("budgetLimitTokens", "x")));
    }

    @Test
    void 列表项被解析为ID() {
        RuntimePolicy adjusted = policy.adjust(one("modelFallbackChain",
                Arrays.asList("m2", "m3")));
        assertEquals(2, adjusted.modelFallbackChain().size());
        assertEquals("m3", adjusted.modelFallbackChain().get(1).getValue());

        RuntimePolicy trusted = policy.adjust(one("trustedToolIds", Arrays.asList("t1")));
        assertTrue(trusted.isToolTrusted(io.github.zcd0831.jellyfish.domain.shared.ToolId.of("t1")));
    }

    @Test
    void 反思阈值高于重试上限时永不反思() {
        RuntimePolicy never = policy.adjust(one("reflectionThreshold", 10));
        assertTrue(!never.needsReflection(3));
    }
}
