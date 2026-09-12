package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.apache.commons.lang3.StringUtils;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置文本绑定器：把 JSON 文本解析成树、在字符串值中替换环境变量占位符，再绑定到目标类型。
 * <p>
 * 之所以先解析再替换，而不是直接对文本做正则替换：
 * <ul>
 *     <li>环境变量值里可能含有 {@code "}、{@code \} 等字符，直接拼文本会破坏 JSON；</li>
 *     <li>只有「字符串值」应当替换，JSON 的 key 以及未来 agents.json 里的提示词模板需要可控语义。</li>
 * </ul>
 * 占位符语法：
 * <ul>
 *     <li>{@code ${VAR}}：必需，环境变量缺失时抛 {@link JellyfishException}；</li>
 *     <li>{@code ${VAR:-default}}：可选，环境变量缺失时使用 {@code default}（可为空）；</li>
 *     <li>{@code \${VAR}}：转义，输出字面量 {@code ${VAR}}，不做替换。</li>
 * </ul>
 *
 * @author zcd
 */
@Singleton
public class SettingsBinder {

    /** 环境变量占位符：可选反斜杠前缀用于转义，花括号内为 {@code 名称} 或 {@code 名称:-默认值}。 */
    private static final Pattern ENV_PATTERN = Pattern.compile("\\\\?\\$\\{([^{}]*)}");

    /** 可选默认值的分隔符。 */
    private static final String DEFAULT_SEPARATOR = ":-";

    /** 环境变量取值函数，默认为 {@link System#getenv(String)}，测试中可替换。 */
    private final Function<String, String> envProvider;

    /**
     * 无状态绑定器，构造器仅供 Dagger 注入。
     */
    @Inject
    public SettingsBinder() {
        this(System::getenv);
    }

    /**
     * 测试用构造器：注入自定义的环境变量取值函数。
     *
     * @param envProvider 环境变量名到值的映射函数，无对应变量时返回 {@code null}
     */
    SettingsBinder(Function<String, String> envProvider) {
        this.envProvider = envProvider;
    }

    /**
     * 解析 JSON 文本、替换字符串值中的环境变量占位符，并绑定到目标类型。
     *
     * @param json   配置文本
     * @param type   目标类型
     * @param source 配置来源描述，仅用于异常信息，可为 {@code null}
     * @param <T>    目标类型
     * @return 绑定结果；{@code json} 为空或解析为 {@code null} 时返回 {@code null}
     * @throws JellyfishException JSON 非法、绑定失败或必需的环境变量缺失时抛出
     */
    public <T> T bind(String json, Class<T> type, String source) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        JsonNode root = ObjectMapperWrapper.readTree(json);
        if (root == null || root.isNull() || root.isMissingNode()) {
            return null;
        }
        substituteEnv(root, source);
        return ObjectMapperWrapper.treeToValue(root, type);
    }

    /**
     * 递归替换 JSON 树中字符串值里的环境变量占位符。
     *
     * @param node   JSON 节点
     * @param source 配置来源描述，仅用于异常信息
     */
    private void substituteEnv(JsonNode node, String source) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            List<String> fieldNames = new ArrayList<>();
            objectNode.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                JsonNode value = objectNode.get(fieldName);
                if (value != null && value.isTextual()) {
                    objectNode.set(fieldName, TextNode.valueOf(substitute(value.textValue(), source)));
                } else {
                    substituteEnv(value, source);
                }
            }
            return;
        }
        if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (int i = 0; i < arrayNode.size(); i++) {
                JsonNode value = arrayNode.get(i);
                if (value != null && value.isTextual()) {
                    arrayNode.set(i, TextNode.valueOf(substitute(value.textValue(), source)));
                } else {
                    substituteEnv(value, source);
                }
            }
        }
    }

    /**
     * 替换单个字符串中的环境变量占位符。
     *
     * @param text   原始字符串
     * @param source 配置来源描述，仅用于异常信息
     * @return 替换后的字符串
     * @throws JellyfishException 必需的环境变量缺失时抛出
     */
    private String substitute(String text, String source) {
        Matcher matcher = ENV_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group();
            if (token.startsWith("\\")) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(token.substring(1)));
                continue;
            }
            String expression = matcher.group(1);
            if (expression.isEmpty()) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(token));
                continue;
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(resolve(expression, source)));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * 解析占位符表达式并取出对应的环境变量值。
     *
     * @param expression {@code 名称} 或 {@code 名称:-默认值}
     * @param source     配置来源描述，仅用于异常信息
     * @return 环境变量值或默认值
     * @throws JellyfishException 必需的环境变量缺失时抛出
     */
    private String resolve(String expression, String source) {
        int separatorIndex = expression.indexOf(DEFAULT_SEPARATOR);
        boolean optional = separatorIndex >= 0;
        String name = optional ? expression.substring(0, separatorIndex) : expression;
        String value = envProvider.apply(name);
        if (value != null) {
            return value;
        }
        if (optional) {
            return expression.substring(separatorIndex + DEFAULT_SEPARATOR.length());
        }
        throw new JellyfishException("environment variable is not set: " + name
                + (StringUtils.isBlank(source) ? "" : ", source: " + source));
    }
}
