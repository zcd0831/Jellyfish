package io.github.zcd0831.jellyfish.domain.action.agent;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 规则出处：05-action §5.6。 */
class PromptTemplateTest {

    @Test
    void 替换已声明变量() {
        PromptTemplate template = new PromptTemplate("你是 ${role}，负责 ${task}。",
                Arrays.asList("role", "task"));

        Map<String, String> values = new HashMap<>();
        values.put("role", "审查员");
        values.put("task", "评审代码");

        assertEquals("你是 审查员，负责 评审代码。", template.render(values));
    }

    @Test
    void 未提供的变量保留原样() {
        PromptTemplate template = new PromptTemplate("你是 ${role}。", Arrays.asList("role"));

        assertEquals("你是 ${role}。", template.render(new HashMap<String, String>()));
    }

    @Test
    void 未声明的形似占位符视为普通文本() {
        PromptTemplate template = new PromptTemplate("保留 ${unknown}", Arrays.asList("role"));

        Map<String, String> values = new HashMap<>();
        values.put("unknown", "不该生效");

        assertEquals("保留 ${unknown}", template.render(values));
    }
}
