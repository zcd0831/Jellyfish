package zcd.jellyfish.plugin.compact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zcd.jellyfish.api.extension.CompactionStrategy;
import zcd.jellyfish.api.extension.CompactionStrategyRequest;
import zcd.jellyfish.api.extension.CompactionTrigger;
import zcd.jellyfish.api.plugin.PluginContext;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CompactionStrategyContribution} 的单元测试：本插件的处理器只做一件事——
 * 把摘要指令与两项配置拼成内核要的策略对象。
 * <p>
 * 摘要指令用<b>真实</b>资源（{@link SummaryPrompt#load()}）：那条链路（资源在本插件 jar 里、
 * 是 UTF-8、占位符在）正是「打包错了就没有摘要指令」的唯一防线，mock 掉它等于把防线也 mock 掉。
 *
 * @author zcd
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("压缩策略贡献")
class CompactionStrategyContributionTest {

    /** 插件上下文。 */
    @Mock
    private PluginContext context;

    @Test
    @DisplayName("处理器给出摘要指令，并把配置里的两项原样交给内核")
    void handle_should_returnPromptAndConfiguredNumbers() {
        Map<String, Object> configuration = new HashMap<String, Object>();
        configuration.put(PluginConfig.KEY_KEEP_RECENT_MESSAGES, 8);
        configuration.put(PluginConfig.KEY_MAX_SUMMARY_CHARS, 1200);
        CompactionStrategyContribution contribution =
                new CompactionStrategyContribution(SummaryPrompt.load(), PluginConfig.from(configuration));

        CompactionStrategy strategy = contribution.handle(request());

        assertTrue(strategy.getSummaryPrompt().contains(CompactionStrategy.MAX_CHARS_PLACEHOLDER),
                strategy.getSummaryPrompt());
        assertEquals(8, strategy.getKeepRecentMessages());
        assertEquals(1200, strategy.getMaxSummaryChars());
    }

    @Test
    @DisplayName("未配置时两项都是 null：不表态让内核用 react 段的缺省值")
    void handle_should_leaveNumbersNull_when_notConfigured() {
        CompactionStrategyContribution contribution =
                new CompactionStrategyContribution(SummaryPrompt.load(), PluginConfig.from(null));

        CompactionStrategy strategy = contribution.handle(request());

        assertNotNull(strategy.getSummaryPrompt());
        assertNull(strategy.getKeepRecentMessages());
        assertNull(strategy.getMaxSummaryChars());
    }

    @Test
    @DisplayName("插件启动即注册策略：注册与依赖装配的次序由插件自己保证")
    void start_should_registerStrategy() {
        when(context.configuration()).thenReturn(Collections.<String, Object>emptyMap());

        new CompactPlugin().start(context);

        verify(context).contribute(eq(CompactionStrategyRequest.class),
                any(CompactionStrategyContribution.class));
    }

    /**
     * 构造一个最小请求：本插件不看请求内容，因此数字随便填。
     *
     * @return 策略请求
     */
    private static CompactionStrategyRequest request() {
        return new CompactionStrategyRequest("s-1", CompactionTrigger.MANUAL, 30, 10, 20, 4000, 100_000L, "gpt-4o");
    }
}
