package io.github.zcd0831.jellyfish.domain.memory.reflection;

import io.github.zcd0831.jellyfish.domain.shared.PlanId;
import io.github.zcd0831.jellyfish.domain.shared.ReflectionId;
import io.github.zcd0831.jellyfish.domain.shared.StepId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 归属唯一 + 落库 raise。规则出处：07-memory §7.8.2 规则 5⑥、§7.7。 */
class ReflectionTest {

    @Test
    void 同一个三元组得到同一个ID() {
        ReflectionId first = Reflection.identify(PlanId.of("p1"), StepId.of("s1"), 2);
        ReflectionId second = Reflection.identify(PlanId.of("p1"), StepId.of("s1"), 2);
        ReflectionId another = Reflection.identify(PlanId.of("p1"), StepId.of("s1"), 3);

        assertEquals(first, second);
        org.junit.jupiter.api.Assertions.assertNotEquals(first, another);
    }

    @Test
    void 落库即raise事件且结论不可变() {
        Reflection reflection = Reflection.record(PlanId.of("p1"), StepId.of("s1"), 1, "先看日志", 100L);

        assertEquals("先看日志", reflection.conclusion());
        assertEquals(1, reflection.pullEvents().size());
        assertEquals("reflection.recorded", reflection.pullEvents().isEmpty()
                ? "reflection.recorded" : "unexpected");
    }
}
