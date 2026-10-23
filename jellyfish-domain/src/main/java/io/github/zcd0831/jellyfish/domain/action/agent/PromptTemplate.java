package io.github.zcd0831.jellyfish.domain.action.agent;

import lombok.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基础提示词模板：占位符固定 `${name}`；未提供的变量保留原样，让缺口可见。
 * 规则出处：模块-行动 §5（PromptTemplate）、技术决策 M08。
 */
@Value
public class PromptTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)\\}");

    String templateText;
    List<String> variables;

    public PromptTemplate(String templateText, List<String> variables) {
        this.templateText = templateText;
        this.variables = Collections.unmodifiableList(new ArrayList<String>(
                variables == null ? Collections.<String>emptyList() : variables));
    }

    public String render(Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(templateText);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = variables.contains(name) && values != null && values.containsKey(name)
                    ? values.get(name)
                    : matcher.group(0);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
