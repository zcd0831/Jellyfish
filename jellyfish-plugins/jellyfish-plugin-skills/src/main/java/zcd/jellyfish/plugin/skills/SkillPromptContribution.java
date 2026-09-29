package zcd.jellyfish.plugin.skills;

import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PromptContribution;
import zcd.jellyfish.api.extension.PromptContributionRequest;

/**
 * 清单贡献：把「有哪些 skill」常驻进 system prompt，这是渐进披露的第一层。
 * <p>
 * <b>为什么走提示词贡献而不是工具参数的 enum</b>：{@code ToolDescriptor} 在注册那一刻就固定，
 * 而 skill 是目录里现扫出来的。走贡献就等于每轮现算，新增一个 skill 下一轮模型就能看见；
 * 走 enum 则要重新注册工具——而「重新注册」在窗口放宽之前根本做不到（见 {@code PluginContext}）。
 * <p>
 * <b>为什么只给名称与描述</b>：正文可能很长，而绝大多数 skill 在这一轮并不会被用到。
 * 常驻的只有模型用来判断「要不要用它」的最小信息，这既是主流 Agent Skills 的做法，
 * 也是把「每轮都要付一次 token」这件事控制在可解释范围内的唯一办法。
 * <p>
 * <b>没有 skill 时返回空贡献</b>：内核会把空贡献整段丢掉，模型因此看不到「可用 skills：」
 * 后面什么都没有的废话。项目里没有 skills 目录是常态，不该表现成一条空清单。
 * <p>
 * 无状态，可安全复用。
 *
 * @author zcd
 */
final class SkillPromptContribution
        implements ExtensionHandler<PromptContributionRequest, PromptContribution> {

    /** skill 目录缓存。 */
    private final SkillCatalog catalog;

    /** 配置：描述展示长度。 */
    private final SkillsConfig config;

    /**
     * 构造贡献处理器。
     *
     * @param catalog skill 目录缓存，不可为 {@code null}
     * @param config  配置，不可为 {@code null}
     */
    SkillPromptContribution(SkillCatalog catalog, SkillsConfig config) {
        this.catalog = catalog;
        this.config = config;
    }

    /**
     * 组装「可用 skills」清单。
     *
     * @param request 贡献请求（本处理器只读，不需要它携带的会话标识）
     * @return 贡献结果；没有 skill 时为空贡献
     */
    @Override
    public PromptContribution handle(PromptContributionRequest request) {
        SkillScanResult snapshot = catalog.current();
        if (snapshot.isEmpty()) {
            return PromptContribution.empty();
        }
        StringBuilder text = new StringBuilder("可用 skills（相关时先用 ")
                .append(SkillTool.NAME).append(" 工具加载其正文，name 取下列之一）：");
        for (SkillDefinition skill : snapshot.skills()) {
            text.append("\n- ").append(skill.name()).append('：')
                    .append(SkillText.singleLine(skill.description(), config.maxDescriptionChars()));
        }
        return PromptContribution.of(text.toString());
    }
}
