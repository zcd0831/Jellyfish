package zcd.jellyfish.plugin.project;

import zcd.jellyfish.api.extension.PromptContribution;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 提示词贡献的会话级缓存：<b>一个会话只读一次盘</b>。
 * <p>
 * <b>为什么要缓存</b>：{@code PromptContributionRequest} 在**每次组装请求时**都会下发，
 * 也就是每一轮对话都会问一遍。不缓存的话，内联模式要每轮把约定文件重读一遍——把磁盘 I/O
 * 押在帧率上是我们已经不想要的代价。业界同源的做法也是这样：Codex 每次运行构建一次指令链，
 * Claude Code 在对话开始时加载 {@code CLAUDE.md}，中途改动都要等到下一次会话。
 * <p>
 * <b>代价是「会话中途修改 {@code AGENTS.md} 不生效」</b>：这是本设计有意接受的行为，
 * 与上面两家一致。要让它生效，开一个新会话即可。
 * <p>
 * <b>为什么按会话而不是全局缓存一次</b>：不同会话的创建时刻不同，全局缓存会把「新会话应该重读」
 * 这件事吞掉；而 {@code /resume} 出来的会话在新进程里缓存本就是空的，因此「新会话生效」这条
 * 语义是自然得到的，不需要额外判断。
 * <p>
 * <b>为什么还要有个条数上限</b>：正常路径由 {@code SessionClosedEvent} 淘汰，但那只在事件真的送达时
 * 才成立（事件通道允许丢弃）。上限是兜底：LRU 淘汰最久没用到的会话，最坏情况退化成「多读一次盘」，
 * 而不会让一个长期进程的缓存无界增长。
 * <p>
 * 线程安全：{@code PromptContributionRequest} 由 {@code react} 池线程并发下发，全部入口同步。
 *
 * @author zcd
 */
final class ContributionCache {

    /** 缓存条数上限。 */
    private static final int MAX_ENTRIES = 64;

    /** 会话标识 → 该会话已经算好的贡献。 */
    private final Map<String, PromptContribution> entries = new LruEntries();

    /**
     * 取出该会话的贡献，没有就算一次并缓存。
     * <p>
     * <b>会话标识为空时不缓存</b>：没有键就无从归属，硬塞进一个哨兵键会让「谁读的盘」这件事失真；
     * 这种情形下退回「每轮现算」，代价与缓存引入之前一样，不会更坏。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @param loader    首次访问时的计算逻辑，不可为 {@code null}
     * @return 该会话的贡献，保证非 {@code null}
     */
    PromptContribution get(String sessionId, Supplier<PromptContribution> loader) {
        if (sessionId == null) {
            return loader.get();
        }
        PromptContribution cached = cached(sessionId);
        if (cached != null) {
            return cached;
        }
        // 计算放在锁外：内联模式要读盘，把磁盘 I/O 押在互斥锁上会让别的会话白等
        // （并发下可能重复读一次，代价远小于让所有请求排在一把锁上）
        PromptContribution computed = loader.get();
        store(sessionId, computed);
        return computed;
    }

    /**
     * 丢弃某个会话的缓存。
     *
     * @param sessionId 会话标识，可为 {@code null}（忽略）
     */
    synchronized void evict(String sessionId) {
        if (sessionId != null) {
            entries.remove(sessionId);
        }
    }

    /**
     * 读取缓存项（按访问顺序访问，参与 LRU 排序）。
     *
     * @param sessionId 会话标识
     * @return 缓存项；未缓存时为 {@code null}
     */
    private synchronized PromptContribution cached(String sessionId) {
        return entries.get(sessionId);
    }

    /**
     * 写入缓存项。
     *
     * @param sessionId    会话标识
     * @param contribution 贡献
     */
    private synchronized void store(String sessionId, PromptContribution contribution) {
        entries.put(sessionId, contribution);
    }

    /**
     * 当前缓存的会话数。
     *
     * @return 条数
     */
    synchronized int size() {
        return entries.size();
    }

    /**
     * 按访问顺序淘汰最旧项的映射。
     * <p>
     * 写成具名内部类而不是匿名类：{@code LinkedHashMap} 是可序列化的，匿名子类会让 Sonar 追着要
     * {@code serialVersionUID}，而这里根本不需要序列化语义。
     *
     * @author zcd
     */
    private static final class LruEntries extends LinkedHashMap<String, PromptContribution> {

        /** 序列化标识（本类从不序列化，仅为满足 Serializable 约定）。 */
        private static final long serialVersionUID = 1L;

        /**
         * 构造按访问顺序排序的映射。
         */
        LruEntries() {
            super(16, 0.75f, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, PromptContribution> eldest) {
            return size() > MAX_ENTRIES;
        }
    }
}
