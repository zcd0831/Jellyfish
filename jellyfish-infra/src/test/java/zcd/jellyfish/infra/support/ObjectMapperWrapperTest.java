package zcd.jellyfish.infra.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.config.AgentDefinition;
import zcd.jellyfish.infra.config.AgentSettings;
import zcd.jellyfish.infra.config.AppConfig;
import zcd.jellyfish.infra.config.JellyfishSettings;
import zcd.jellyfish.infra.config.ModelSettings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ObjectMapperWrapper#unknownFields} 的单元测试。
 * <p>
 * 这个方法存在的理由只有一个：绑定刻意容忍未知字段（配置文件要向前兼容），于是<b>拼错一个字段名完全无声</b>。
 * 因此它必须做到两件相反的事——<b>拼错要报出来</b>，而<b>合法的配置一个都不能误报</b>（误报会让这条告警
 * 变成噪音，用户很快学会忽略它）。这两面各有用例，第二面用一份「把所有受管字段都写满」的配置来钉。
 *
 * @author zcd
 */
@DisplayName("ObjectMapperWrapper 未知字段比对")
class ObjectMapperWrapperTest {

    /** 一份把受管字段写满的 models.json（字段名逐个来自各配置类的 {@code @JsonProperty}）。 */
    private static final String COMPLETE_MODELS = "{"
            + "\"defaultProvider\": \"openai\","
            + "\"defaultModel\": \"gpt-4o\","
            + "\"providers\": {"
            + "  \"openai\": {"
            + "    \"name\": \"openai\","
            + "    \"type\": \"openai\","
            + "    \"apiKey\": \"${OPENAI_API_KEY:-}\","
            + "    \"baseUrl\": \"https://api.openai.com/v1\","
            + "    \"cache\": {\"promptCacheKey\": true, \"keepAliveSeconds\": 60},"
            + "    \"sampling\": {\"temperature\": 0.2, \"topP\": 0.9, \"topK\": 40, \"seed\": 7,"
            + "                   \"frequencyPenalty\": 0.1, \"presencePenalty\": 0.1, \"stop\": [\"</done>\"]},"
            + "    \"vendorBody\": {\"reasoning_effort\": \"low\"},"
            + "    \"vendorHeaders\": {\"x-tenant\": \"team-a\"},"
            + "    \"models\": [{"
            + "      \"id\": \"gpt-4o\", \"name\": \"gpt-4o\", \"contextLength\": 128000,"
            + "      \"maxOutputTokens\": 4096, \"maxTokensField\": \"max_tokens\","
            + "      \"sampling\": {\"temperature\": 1},"
            + "      \"vendorBody\": {\"service_tier\": \"auto\"}"
            + "    }]"
            + "  }"
            + "}"
            + "}";

    /** 一份把受管字段写满的 agents.json。 */
    private static final String COMPLETE_AGENTS = "{\"agents\": {"
            + "  \"jellyfish\": {"
            + "    \"agentId\": \"jellyfish\","
            + "    \"description\": \"系统默认 agent\","
            + "    \"permissions\": {\"deniedTools\": [\"shell\"], \"askTools\": [\"write\"],"
            + "                     \"allowedTools\": [\"read\"]},"
            + "    \"delegatable\": true,"
            + "    \"model\": \"gpt-4o\""
            + "  }"
            + "}}";

    /** 一份把受管字段写满的 jellyfish.json（含自由格式的逐插件配置段）。 */
    private static final String COMPLETE_SETTINGS = "{"
            + "\"plugins\": {"
            + "  \"enabled\": [\"jellyfish-plugin-tools\"],"
            + "  \"disabled\": [],"
            // 逐插件配置段的内容由插件自己定义，键名不该被内核指手画脚
            + "  \"configurations\": {\"jellyfish-plugin-tools\": {\"workspace\": \"/tmp\", \"任意键\": 1}}"
            + "},"
            + "\"react\": {"
            + "  \"maxRounds\": 12, \"contextReserveTokens\": 4096, \"maxToolOutputChars\": 1000,"
            + "  \"compactKeepRecentMessages\": 6, \"compactMaxSummaryChars\": 2000,"
            + "  \"autoCompactPercent\": 80,"
            + "  \"toolOutput\": {\"dir\": \"/tmp\", \"keepFiles\": 10, \"maxBytes\": 1000,"
            + "                  \"spillMaxBytes\": 2000, \"keepRecentMessages\": 3},"
            + "  \"cache\": {\"agingPercent\": 50}"
            + "},"
            + "\"permission\": {\"approvalTimeoutSeconds\": 120},"
            + "\"subAgent\": {"
            + "  \"enabled\": true, \"maxDepth\": 2, \"maxSpawnsPerTurn\": 4, \"maxRounds\": 8,"
            + "  \"maxConcurrentRuns\": 2, \"maxQueuedRuns\": 8, \"runTimeoutMillis\": 600000,"
            + "  \"runTokenBudget\": 100000, \"treeTokenBudget\": 400000,"
            + "  \"archiveKeepFiles\": 20, \"archiveMaxBytes\": 1000000"
            + "},"
            + "\"ask\": {\"timeoutSeconds\": 120}"
            + "}";

    @Test
    @DisplayName("写满受管字段的配置一个都不报（误报会让这条告警变成噪音）")
    void unknownFields_should_reportNothing_whenConfigUsesOnlyKnownFields() {
        // When / Then
        assertEquals(Collections.emptyList(), ObjectMapperWrapper.unknownFields(COMPLETE_MODELS, ModelSettings.class));
        assertEquals(Collections.emptyList(), ObjectMapperWrapper.unknownFields(COMPLETE_AGENTS, AgentSettings.class));
        assertEquals(Collections.emptyList(), ObjectMapperWrapper.unknownFields(COMPLETE_SETTINGS, JellyfishSettings.class));
    }

    @Test
    @DisplayName("拼错的字段名报出来，并带得出它在哪一层")
    void unknownFields_should_reportTypo_withItsPath() {
        // Given：三处拼错——顶层、provider 里、agent 里，每一层都要能被定位
        String json = "{"
                + "\"defaultModle\": \"gpt-4o\","
                + "\"providers\": {\"openai\": {\"vondorBody\": {\"a\": 1}}}"
                + "}";

        // When
        List<String> unknown = ObjectMapperWrapper.unknownFields(json, ModelSettings.class);

        // Then
        assertEquals(2, unknown.size(), unknown.toString());
        assertTrue(unknown.contains("defaultModle"), unknown.toString());
        assertTrue(unknown.contains("providers.openai.vondorBody"), unknown.toString());
    }

    @Test
    @DisplayName("自由格式的键名不报（vendorBody/逐插件配置段的键由使用方决定）")
    void unknownFields_should_skipFreeFormSections() {
        // Given：两个自由格式段里塞了内核完全不认识的键
        String models = "{\"providers\": {\"p\": {\"vendorBody\": {\"whatever\": 1},"
                + " \"vendorHeaders\": {\"任意头\": \"v\"}}}}";
        String settings = "{\"plugins\": {\"configurations\": {\"some-plugin\": {\"任意键\": 1}}}}";

        // When / Then
        assertEquals(Collections.emptyList(), ObjectMapperWrapper.unknownFields(models, ModelSettings.class));
        assertEquals(Collections.emptyList(), ObjectMapperWrapper.unknownFields(settings, JellyfishSettings.class));
    }

    @Test
    @DisplayName("数组元素里的拼错也能定位到下标")
    void unknownFields_should_reportTypoInsideArray() {
        // Given：models 数组里的第二个元素拼错了字段
        String json = "{\"providers\": {\"p\": {\"models\": [{"
                + "\"id\": \"a\", \"name\": \"a\", \"contextLength\": 1, \"maxOutputTokens\": 1"
                + "}, {\"id\": \"b\", \"contextLenght\": 2}]}}}";

        // When
        List<String> unknown = ObjectMapperWrapper.unknownFields(json, ModelSettings.class);

        // Then
        assertEquals(Collections.singletonList("providers.p.models[1].contextLenght"), unknown);
    }

    @Test
    @DisplayName("空文本、非法 JSON、空类型都不抛异常，只是什么都报不出来")
    void unknownFields_should_returnEmpty_whenInputIsUnusable() {
        // When / Then：解析失败是绑定那条路上的事，这里不该抢着报
        assertEquals(Collections.emptyList(), ObjectMapperWrapper.unknownFields(null, ModelSettings.class));
        assertEquals(Collections.emptyList(), ObjectMapperWrapper.unknownFields("   ", ModelSettings.class));
        assertEquals(Collections.emptyList(), ObjectMapperWrapper.unknownFields("{", ModelSettings.class));
        assertEquals(Collections.emptyList(), ObjectMapperWrapper.unknownFields(COMPLETE_MODELS, null));
    }

    @Test
    @DisplayName("内核自己的两份真实配置文件不报任何未知字段")
    void unknownFields_should_reportNothing_forResourcesShippedByTheKernel() throws IOException {
        // Given：仓库里真实存在的配置（default-agent.json 是内核自带的 agent 定义）
        String agent = readResource("/default-agent.json");

        // When / Then：我们自己写的那份配置如果都拼不对，这条告警就没有可信度了
        assertEquals(Collections.emptyList(), ObjectMapperWrapper.unknownFields(agent, AgentDefinition.class));
        assertEquals(Collections.emptyList(),
                ObjectMapperWrapper.unknownFields("{\"processName\": \"p\","
                        + " \"model\": {\"globalPath\": \"\", \"projectPath\": \"\"},"
                        + " \"agent\": {\"globalPath\": \"\", \"projectPath\": \"\"},"
                        + " \"jellyfish\": {\"globalPath\": \"\", \"projectPath\": \"\"},"
                        + " \"plugins\": {\"roots\": []}}", AppConfig.class));
    }

    /**
     * 读一份 classpath 资源。
     *
     * @param path 资源路径（以 {@code /} 开头）
     * @return 文本内容
     * @throws IOException 读不出来时抛出
     */
    private static String readResource(String path) throws IOException {
        try (InputStream in = ObjectMapperWrapperTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("资源不存在: " + path);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
