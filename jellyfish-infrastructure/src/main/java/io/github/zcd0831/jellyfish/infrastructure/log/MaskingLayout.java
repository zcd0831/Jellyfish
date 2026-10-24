package io.github.zcd0831.jellyfish.infrastructure.log;

import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.layout.AbstractStringLayout;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在适配器统一脱敏：key 命中敏感词的字段值一律替换为 `***`，不靠调用方自觉。
 * 规则出处：技术决策 D14-2 规则 3、D18-3。
 */
@Plugin(name = "MaskingLayout", category = Node.CATEGORY, elementType = Layout.ELEMENT_TYPE,
        printObject = true)
public final class MaskingLayout extends AbstractStringLayout {

    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)\\b(key|token|secret|password|authorization|cookie)=(\\S+)");

    private final PatternLayout delegate;

    private MaskingLayout(PatternLayout delegate, Charset charset) {
        super(charset);
        this.delegate = delegate;
    }

    @PluginFactory
    public static MaskingLayout createLayout(@PluginAttribute("pattern") String pattern) {
        PatternLayout delegate = PatternLayout.newBuilder()
                .withPattern(pattern == null ? "%d{ISO8601} %-5p %m%n" : pattern)
                .build();
        return new MaskingLayout(delegate, Charset.forName("UTF-8"));
    }

    @Override
    public String toSerializable(LogEvent event) {
        return mask(delegate.toSerializable(event));
    }

    static String mask(String line) {
        if (line == null) {
            return null;
        }
        Matcher matcher = SENSITIVE.matcher(line);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(matcher.group(1) + "=***"));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
