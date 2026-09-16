package zcd.jellyfish.plugin.project;

import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PromptContribution;
import zcd.jellyfish.api.extension.PromptContributionRequest;

/**
 * 提示词贡献：把工作目录下的项目约定交给模型。
 * <p>
 * <b>两种形态，按文件大小分流</b>：
 * <ul>
 *     <li><b>装得进上限</b>（默认 32 KiB）→ 内联<b>原文</b>。常见项目的 {@code AGENTS.md} 只有几十行，
 *     直接给全文可以让模型省下「先读一次文件」的往返，也省掉「它忘了读」这个失败模式；</li>
 *     <li><b>超过上限</b> → 只给<b>路径</b>，并附上实际大小。大文件每轮都要付一遍 token，
 *     而它多半是参考性内容，让模型按需分段读更划算。</li>
 * </ul>
 * <b>不做「内联前 N KB + 给路径」</b>：头部往往恰是信息量最低的部分，而且截断点落在哪、
 * 模型知不知道「后面还有」，都是新的失败模式。两态分明比三态含糊好。
 * <p>
 * <b>内联是刻意的安全姿态选择</b>：原文进的是 system prompt，也就是仓库内容拿到了最高优先级的话语权。
 * 这是为了让常见的小文件开箱即用而付的代价，护栏有三条：内联块开头的定性句（声明这是数据）、
 * 足够小的上限、以及 {@code maxInlineBytes: 0} 这个「彻底关掉内联」的开关。
 * <p>
 * <b>每个会话只算一次</b>：结果按 {@code sessionId} 缓存在 {@link ContributionCache} 里，
 * 会话中途修改 {@code AGENTS.md} 不生效，新会话才重读。理由见缓存类注释。
 * <p>
 * 线程安全：依赖 {@link ConventionFiles}（无状态）与 {@link ContributionCache}（同步），
 * 自身不持有可变状态。
 *
 * @author zcd
 */
final class ProjectPromptContribution implements ExtensionHandler<PromptContributionRequest, PromptContribution> {

    /** 约定文件探测器。 */
    private final ConventionFiles files;

    /** 本插件配置。 */
    private final PluginConfig config;

    /** 会话级缓存。 */
    private final ContributionCache cache;

    /**
     * 构造贡献处理器。
     *
     * @param files  约定文件探测器，不可为 {@code null}
     * @param config 本插件配置，不可为 {@code null}
     * @param cache  会话级缓存，不可为 {@code null}
     */
    ProjectPromptContribution(ConventionFiles files, PluginConfig config, ContributionCache cache) {
        this.files = files;
        this.config = config;
        this.cache = cache;
    }

    @Override
    public PromptContribution handle(PromptContributionRequest request) {
        return cache.get(request.getSessionId(), this::load);
    }

    /**
     * 丢弃某个会话的缓存：会话关闭（或删除）时由插件调用。
     *
     * @param sessionId 会话标识，可为 {@code null}
     */
    void evict(String sessionId) {
        cache.evict(sessionId);
    }

    /**
     * 现算一次该会话的贡献。
     * <p>
     * <b>没有命中时返回空贡献</b>：本处理器每次组装请求都会被问到，而绝大多数工作目录并没有约定文件——
     * {@link PromptContribution#empty()} 正是这个扩展点为「无事可说」留的表达，内核不会为它追加任何块
     * （连空标题都不会出现）。
     *
     * @return 贡献结果，保证非 {@code null}
     */
    private PromptContribution load() {
        ConventionFile file = files.probe();
        if (file == null) {
            return PromptContribution.empty();
        }
        if (!file.fitsWithin(config.maxInlineBytes())) {
            return PromptContribution.of(ConventionText.guidance(file));
        }
        ConventionFiles.Reading reading = files.read(file, config.maxInlineBytes());
        // 读取失败时退回路径指引：模型的读取工具会报出真实原因（权限、编码……）
        return reading == null ? PromptContribution.of(ConventionText.guidance(file))
                : PromptContribution.of(ConventionText.inline(file, reading));
    }
}
