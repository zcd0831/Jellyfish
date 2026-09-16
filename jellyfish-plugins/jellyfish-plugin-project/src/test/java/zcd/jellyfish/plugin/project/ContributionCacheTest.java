package zcd.jellyfish.plugin.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.extension.PromptContribution;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link ContributionCache} 的单元测试：按会话只算一次、会话结束可丢弃、条数有界。
 *
 * @author zcd
 */
@DisplayName("提示词贡献会话缓存")
class ContributionCacheTest {

    /** 缓存条数上限，与实现保持一致（用来验证兜底淘汰真的会发生）。 */
    private static final int EXPECTED_MAX_ENTRIES = 64;

    @Test
    @DisplayName("同一会话第二次应直接命中缓存，不再现算")
    void get_should_computeOnlyOnce_when_sameSession() {
        ContributionCache cache = new ContributionCache();
        AtomicInteger loads = new AtomicInteger();

        PromptContribution first = cache.get("s-1", counting(loads));
        PromptContribution second = cache.get("s-1", counting(loads));

        assertEquals(1, loads.get());
        assertSame(first, second);
    }

    @Test
    @DisplayName("不同会话各算一次：新会话必须重读，否则「新会话生效」这条语义会丢")
    void get_should_computePerSession() {
        ContributionCache cache = new ContributionCache();
        AtomicInteger loads = new AtomicInteger();

        cache.get("s-1", counting(loads));
        cache.get("s-2", counting(loads));

        assertEquals(2, loads.get());
    }

    @Test
    @DisplayName("丢弃后应重新现算")
    void get_should_recompute_afterEvict() {
        ContributionCache cache = new ContributionCache();
        AtomicInteger loads = new AtomicInteger();
        cache.get("s-1", counting(loads));

        cache.evict("s-1");
        cache.get("s-1", counting(loads));

        assertEquals(2, loads.get());
    }

    @Test
    @DisplayName("会标为空时不缓存：没有键就无从归属，退回每轮现算")
    void get_should_notCache_when_sessionIdIsNull() {
        ContributionCache cache = new ContributionCache();
        AtomicInteger loads = new AtomicInteger();

        cache.get(null, counting(loads));
        cache.get(null, counting(loads));

        assertEquals(2, loads.get());
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("超过条数上限应淘汰最旧项：缓存不得无界增长")
    void get_should_evictOldest_when_full() {
        ContributionCache cache = new ContributionCache();
        for (int i = 0; i <= EXPECTED_MAX_ENTRIES; i++) {
            cache.get("s-" + i, () -> PromptContribution.of("贡献"));
        }

        assertEquals(EXPECTED_MAX_ENTRIES, cache.size());
    }

    /**
     * 构造一个计数并返回贡献的加载逻辑。
     *
     * @param loads 计数器
     * @return 加载逻辑
     */
    private static Supplier<PromptContribution> counting(AtomicInteger loads) {
        return () -> {
            loads.incrementAndGet();
            return PromptContribution.of("贡献-" + loads.get());
        };
    }
}
