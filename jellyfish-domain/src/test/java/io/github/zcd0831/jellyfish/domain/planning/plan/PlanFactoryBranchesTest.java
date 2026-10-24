package io.github.zcd0831.jellyfish.domain.planning.plan;

import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 草案校验的各条拒绝分支。规则出处：模块-规划 §8.4。 */
class PlanFactoryBranchesTest {

    private final PlanFactory factory = new PlanFactory();

    private static Map<String, Object> args(Object steps) {
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("goal", "目标");
        args.put("steps", steps);
        return args;
    }

    private static Map<String, Object> step() {
        Map<String, Object> step = new LinkedHashMap<String, Object>();
        step.put("description", "做事");
        return step;
    }

    private static PlanCreationException rejected(Map<String, Object> args) {
        return assertThrows(PlanCreationException.class, () -> new PlanFactory().create(
                PlanId.of("p1"), SessionId.of("s1"), args, Collections.<ToolId>emptySet()));
    }

    @Test
    void steps缺失或不是数组都拒绝() {
        assertEquals("P_INVALID_PLAN", rejected(args("不是数组")).error().code());
        assertEquals("P_INVALID_PLAN", rejected(args(Collections.singletonList("字符串"))).error().code());
    }

    @Test
    void verifications不是数组或含未知值都拒绝() {
        Map<String, Object> step = step();
        step.put("verifications", "DETERMINISTIC");
        assertTrue(rejected(args(Collections.singletonList(step))).getMessage().contains("数组"));

        Map<String, Object> unknown = step();
        unknown.put("verifications", Collections.singletonList("MAGIC"));
        assertTrue(rejected(args(Collections.singletonList(unknown))).getMessage().contains("未知的验证类型"));
    }

    @Test
    void tools不是数组或引用未绑定都拒绝() {
        Map<String, Object> bad = step();
        bad.put("tools", "read_file");
        assertTrue(rejected(args(Collections.singletonList(bad))).getMessage().contains("tools"));

        Map<String, Object> unbound = step();
        unbound.put("tools", Collections.singletonList("nope"));
        assertTrue(rejected(args(Collections.singletonList(unbound))).getMessage().contains("未绑定"));
    }

    @Test
    void dependsOn不是数组或非数字都拒绝() {
        Map<String, Object> bad = step();
        bad.put("dependsOn", "1");
        assertTrue(rejected(args(Collections.singletonList(bad))).getMessage().contains("dependsOn"));

        Map<String, Object> zero = step();
        zero.put("dependsOn", Collections.singletonList(0));
        assertTrue(rejected(args(Collections.singletonList(zero))).getMessage().contains("非法序号"));

        Map<String, Object> text = step();
        text.put("dependsOn", Collections.singletonList("x"));
        assertTrue(rejected(args(Collections.singletonList(text))).getMessage().contains("非数字"));
    }

    @Test
    void 指定goal被保留() {
        Plan plan = factory.create(PlanId.of("p1"), SessionId.of("s1"),
                args(Collections.singletonList(step())), Collections.<ToolId>emptySet());
        assertEquals("目标", plan.goal());
    }
}
