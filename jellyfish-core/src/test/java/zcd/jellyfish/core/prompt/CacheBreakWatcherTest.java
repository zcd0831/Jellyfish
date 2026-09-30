package zcd.jellyfish.core.prompt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.llm.LlmTool;
import zcd.jellyfish.infra.llm.LlmToolCall;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CacheBreakWatcher} 的单元测试。
 * <p>
 * 这个类的价值全在「报得准」上：漏报等于缓存断裂继续静默，误报等于让人去查不存在的问题。
 * 因此用例分两边——每种断裂都必须报出来，而「只追加」这种唯一健康的形态必须报成健康。
 *
 * @author zcd
 */
@DisplayName("缓存断裂观察器")
class CacheBreakWatcherTest {

    /** 会话标识。 */
    private static final String SESSION = "s-1";

    /** 基准 system prompt。 */
    private static final String SYSTEM = "你是助手";

    /** 基准工具清单。 */
    private static final List<LlmTool> TOOLS = Collections.singletonList(
            new LlmTool("read", "读文件", Collections.singletonMap("path", "string"),
                    Collections.singletonList("path")));

    /** 被测观察器。 */
    private CacheBreakWatcher watcher;

    @BeforeEach
    void setUp() {
        watcher = new CacheBreakWatcher();
    }

    @Test
    @DisplayName("首次观察没有基线，既不报断裂也不告警")
    void observe_should_reportFirstObservation_withoutBaseline() {
        // When
        CacheBreakWatcher.Report report = watcher.observe(SESSION, SYSTEM, TOOLS, messages("一", "二"));

        // Then
        assertTrue(report.isFirstObservation());
        assertFalse(report.isBroken());
        assertFalse(report.isFirstWarning());
    }

    @Test
    @DisplayName("只在尾部追加时前缀完全可复用，不报断裂")
    void observe_should_reportHealthy_when_onlyAppended() {
        // Given
        watcher.observe(SESSION, SYSTEM, TOOLS, messages("一", "二"));

        // When：第二轮只是在尾部多了一条
        CacheBreakWatcher.Report report = watcher.observe(SESSION, SYSTEM, TOOLS, messages("一", "二", "三"));

        // Then
        assertFalse(report.isBroken(), report.describe());
        assertTrue(report.describe().contains("可复用 2 条"), report.describe());
    }

    @Test
    @DisplayName("system prompt 变化必须报出来，并带上长度变化")
    void observe_should_reportBreak_when_systemPromptChanged() {
        // Given
        watcher.observe(SESSION, SYSTEM, TOOLS, messages("一"));

        // When：system prompt 多了 3 个字符
        CacheBreakWatcher.Report report = watcher.observe(SESSION, SYSTEM + "啊", TOOLS, messages("一"));

        // Then：它是请求的第 0 个 token，一变则整段失效
        assertTrue(report.isBroken());
        assertTrue(report.isFirstWarning());
        assertTrue(report.describe().contains("整段请求失效"), report.describe());
        assertTrue(report.describe().contains("+1 字符"), report.describe());
    }

    @Test
    @DisplayName("工具清单变化必须报出来：只改参数也算")
    void observe_should_reportBreak_when_toolDefinitionChanged() {
        // Given
        watcher.observe(SESSION, SYSTEM, TOOLS, messages("一"));

        // When：只把描述改了（名字一模一样）——只比名字的实现会漏掉它
        List<LlmTool> renamed = Collections.singletonList(
                new LlmTool("read", "读文件（改过）", Collections.singletonMap("path", "string"),
                        Collections.singletonList("path")));
        CacheBreakWatcher.Report report = watcher.observe(SESSION, SYSTEM, renamed, messages("一"));

        // Then
        assertTrue(report.isBroken());
        assertTrue(report.describe().contains("工具清单变了"), report.describe());
    }

    @Test
    @DisplayName("工具顺序变化也算断裂：厂商模板按顺序渲染")
    void observe_should_reportBreak_when_toolOrderChanged() {
        // Given
        LlmTool other = new LlmTool("write", "写文件", null, null);
        watcher.observe(SESSION, SYSTEM, Arrays.asList(TOOLS.get(0), other), messages("一"));

        // When：同一批工具、换了顺序
        CacheBreakWatcher.Report report = watcher.observe(SESSION, SYSTEM, Arrays.asList(other, TOOLS.get(0)),
                messages("一"));

        // Then
        assertTrue(report.isBroken(), report.describe());
    }

    @Test
    @DisplayName("历史中段被改写必须报出来，并指出从第几条起失效")
    void observe_should_reportBreak_when_historyRewritten() {
        // Given：三条消息
        watcher.observe(SESSION, SYSTEM, TOOLS, messages("一", "二", "三"));

        // When：第二条变了（形态同「旧工具结果被换成 stub」）
        CacheBreakWatcher.Report report = watcher.observe(SESSION, SYSTEM, TOOLS, messages("一", "二改", "三"));

        // Then：可复用 1 条，第 2 条起失效
        assertTrue(report.isBroken());
        assertTrue(report.describe().contains("可复用 1/3 条"), report.describe());
        assertTrue(report.describe().contains("第 2 条起"), report.describe());
    }

    @Test
    @DisplayName("历史变短也算断裂：裁剪丢头就是这种形态")
    void observe_should_reportBreak_when_historyShrunk() {
        // Given
        watcher.observe(SESSION, SYSTEM, TOOLS, messages("一", "二", "三"));

        // When：前两条被丢掉（crop 从最旧成组丢弃）
        CacheBreakWatcher.Report report = watcher.observe(SESSION, SYSTEM, TOOLS, messages("三"));

        // Then
        assertTrue(report.isBroken(), report.describe());
    }

    @Test
    @DisplayName("工具调用的参数变了也算历史改写：它是消息正文的一部分")
    void observe_should_reportBreak_when_toolCallArgumentsChanged() {
        // Given
        LlmMessage withCall = LlmMessage.assistant(null,
                Collections.singletonList(new LlmToolCall(0, "c1", "read", "{\"path\":\"a\"}")));
        watcher.observe(SESSION, SYSTEM, TOOLS, Collections.singletonList(withCall));

        // When：同一个调用 id 与名字，只有参数变了
        LlmMessage changed = LlmMessage.assistant(null,
                Collections.singletonList(new LlmToolCall(0, "c1", "read", "{\"path\":\"b\"}")));
        CacheBreakWatcher.Report report =
                watcher.observe(SESSION, SYSTEM, TOOLS, Collections.singletonList(changed));

        // Then
        assertTrue(report.isBroken(), report.describe());
    }

    @Test
    @DisplayName("断裂持续期间只告警一次，恢复后再次断裂会重新告警")
    void observe_should_warnOncePerBreakEpisode() {
        // Given：稳定一轮
        watcher.observe(SESSION, SYSTEM, TOOLS, messages("一"));
        watcher.observe(SESSION, SYSTEM, TOOLS, messages("一", "二"));

        // When：连着两轮都在断（形态同「每轮都被老化改写」）
        CacheBreakWatcher.Report firstBreak =
                watcher.observe(SESSION, SYSTEM, TOOLS, messages("一改", "二", "三"));
        CacheBreakWatcher.Report secondBreak =
                watcher.observe(SESSION, SYSTEM, TOOLS, messages("一改", "二改", "三", "四"));

        // Then：两轮都断了，但只有第一轮值得 WARN——否则日志被刷满，反而看不出「什么时候开始的」
        assertTrue(firstBreak.isBroken());
        assertTrue(firstBreak.isFirstWarning());
        assertTrue(secondBreak.isBroken());
        assertFalse(secondBreak.isFirstWarning());

        // When：恢复之后再次断裂
        CacheBreakWatcher.Report recovered = watcher.observe(SESSION, SYSTEM, TOOLS,
                messages("一改", "二改", "三", "四", "五"));
        CacheBreakWatcher.Report brokenAgain = watcher.observe(SESSION, SYSTEM, TOOLS,
                messages("一改", "二改", "三", "四", "五改"));

        // Then：重新告警——否则「断了一次之后再也不报」会让长期会话失去警戒
        assertFalse(recovered.isBroken(), recovered.describe());
        assertTrue(brokenAgain.isFirstWarning());
    }

    @Test
    @DisplayName("会话标识为空时只出结论、不留状态")
    void observe_should_notKeepState_when_sessionIdIsNull() {
        // When
        CacheBreakWatcher.Report first = watcher.observe(null, SYSTEM, TOOLS, messages("一"));
        CacheBreakWatcher.Report second = watcher.observe(null, SYSTEM, TOOLS, messages("一", "二"));

        // Then：没有键就无从归属，两次都是「首次观察」而不是假装有基线
        assertTrue(first.isFirstObservation());
        assertTrue(second.isFirstObservation());
    }

    @Test
    @DisplayName("不同会话各自独立观察，互不干扰")
    void observe_should_keepSessionsIndependent() {
        // Given
        watcher.observe("a", SYSTEM, TOOLS, messages("一"));
        watcher.observe("b", SYSTEM, TOOLS, messages("一"));

        // When
        CacheBreakWatcher.Report report = watcher.observe("a", SYSTEM, TOOLS, messages("一", "二"));

        // Then
        assertFalse(report.isBroken(), report.describe());
        assertFalse(report.isFirstObservation());
    }

    @Test
    @DisplayName("system prompt 为空（不下发）也是稳定状态")
    void observe_should_treatAbsentSystemPromptAsStable() {
        // Given
        watcher.observe(SESSION, null, TOOLS, messages("一"));

        // When / Then
        assertFalse(watcher.observe(SESSION, null, TOOLS, messages("一", "二")).isBroken());
    }

    @Test
    @DisplayName("结论的 toString 不抛异常且带上断裂判定")
    void toString_should_notThrow() {
        // When
        CacheBreakWatcher.Report report = watcher.observe(SESSION, SYSTEM, TOOLS, messages("一"));

        // Then
        assertNotNull(report.toString());
        assertTrue(report.toString().contains("broken=false"));
    }

    /**
     * 构造一批同角色消息，正文带上序号便于断言「哪一条变了」。
     *
     * @param contents 各条正文
     * @return 消息列表
     */
    private static List<LlmMessage> messages(String... contents) {
        List<LlmMessage> list = new java.util.ArrayList<LlmMessage>(contents.length);
        for (String content : contents) {
            list.add(LlmMessage.user(content));
        }
        return list;
    }
}
