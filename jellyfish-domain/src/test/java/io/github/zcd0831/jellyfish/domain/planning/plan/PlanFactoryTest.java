package io.github.zcd0831.jellyfish.domain.planning.plan;

import io.github.zcd0831.jellyfish.domain.planning.verification.VerificationType;
import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 草案校验：失败即工具失败，不消耗重试。规则出处：模块-规划 §8.4。 */
class PlanFactoryTest {

    private final PlanFactory factory = new PlanFactory();

    private static Map<String, Object> step(String description) {
        Map<String, Object> step = new LinkedHashMap<String, Object>();
        step.put("description", description);
        return step;
    }

    private static Map<String, Object> plan(Map<String, Object>... steps) {
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("goal", "目标");
        args.put("steps", new ArrayList<Map<String, Object>>(Arrays.asList(steps)));
        return args;
    }

    @Test
    void 合法草案生成计划并保留验证声明() {
        Map<String, Object> draft = step("读文件");
        draft.put("goal", "读完目标文件");
        draft.put("expectedOutcome", "拿到文件内容");
        draft.put("verificationCriteria", "内容非空");
        draft.put("verifications", Arrays.asList("DETERMINISTIC", "LLM_JUDGE"));

        Plan plan = factory.create(PlanId.of("p1"), SessionId.of("s1"), plan(draft),
                Collections.singleton(ToolId.of("read_file")));

        assertEquals(1, plan.steps().size());
        assertEquals(2, plan.steps().get(0).requiredVerifications().size());
        assertTrue(plan.steps().get(0).requiredVerifications().contains(VerificationType.LLM_JUDGE));
        assertEquals(PlanStatus.EXECUTING, plan.status());
    }

    @Test
    void 缺步骤或缺描述都拒绝() {
        assertThrows(PlanCreationException.class, () -> factory.create(PlanId.of("p1"),
                SessionId.of("s1"), plan(), Collections.<ToolId>emptySet()));
        assertThrows(PlanCreationException.class, () -> factory.create(PlanId.of("p1"),
                SessionId.of("s1"), plan(step(" ")), Collections.<ToolId>emptySet()));
    }

    @Test
    void 声明LLM_JUDGE必须给出三要素() {
        Map<String, Object> draft = step("写报告");
        draft.put("verifications", Arrays.asList("LLM_JUDGE"));

        PlanCreationException e = assertThrows(PlanCreationException.class, () -> factory.create(
                PlanId.of("p1"), SessionId.of("s1"), plan(draft), Collections.<ToolId>emptySet()));
        assertTrue(e.getMessage().contains("goal"), e.getMessage());

        draft.put("goal", "写一份评审意见");
        draft.put("expectedOutcome", "包含三个问题");
        draft.put("verificationCriteria", "问题条数 >= 3");
        Plan created = factory.create(PlanId.of("p1"), SessionId.of("s1"), plan(draft),
                Collections.<ToolId>emptySet());

        assertEquals("写一份评审意见", created.steps().get(0).goal());
        assertEquals("问题条数 >= 3", created.steps().get(0).verificationCriteria());
    }

    @Test
    void 引用未绑定工具拒绝() {
        Map<String, Object> draft = step("跑命令");
        draft.put("tools", Arrays.asList("run_shell"));

        assertThrows(PlanCreationException.class, () -> factory.create(PlanId.of("p1"), SessionId.of("s1"),
                plan(draft), Collections.singleton(ToolId.of("read_file"))));
    }

    @Test
    void 依赖成环或前向引用拒绝() {
        Map<String, Object> first = step("第一步");
        first.put("dependsOn", Arrays.asList(2));
        Map<String, Object> second = step("第二步");
        second.put("dependsOn", Arrays.asList(1));

        PlanCreationException e = assertThrows(PlanCreationException.class, () -> factory.create(
                PlanId.of("p1"), SessionId.of("s1"), plan(first, second), Collections.<ToolId>emptySet()));

        assertEquals("P_INVALID_PLAN", e.error().code());
    }

    @Test
    void 未知验证类型拒绝() {
        Map<String, Object> draft = step("第一步");
        draft.put("verifications", Arrays.asList("MAGIC"));

        assertThrows(PlanCreationException.class, () -> factory.create(PlanId.of("p1"), SessionId.of("s1"),
                plan(draft), Collections.<ToolId>emptySet()));
    }

    @Test
    void 步骤过多拒绝() {
        List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 31; i++) {
            steps.add(step("第 " + i + " 步"));
        }
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("steps", steps);

        assertThrows(PlanCreationException.class, () -> factory.create(PlanId.of("p1"), SessionId.of("s1"),
                args, Collections.<ToolId>emptySet()));
    }
}
