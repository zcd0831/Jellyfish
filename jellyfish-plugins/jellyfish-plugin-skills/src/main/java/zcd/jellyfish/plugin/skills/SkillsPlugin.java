package zcd.jellyfish.plugin.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.extension.CommandDescriptor;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.PromptContributionRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.plugin.JellyfishPlugin;
import zcd.jellyfish.api.plugin.PluginContext;

import java.util.List;

/**
 * 官方 skills 插件：按目录发现 {@code SKILL.md}，元信息常驻 system prompt，正文由模型按需加载。
 * <p>
 * <b>三个扩展点，各承担渐进披露的一层</b>：
 * <ol>
 *     <li>{@link PromptContributionRequest}：名称 + 描述常驻 system prompt（第一层）；</li>
 *     <li>{@link ToolCallRequest} 的 {@code skill} 工具：模型判断相关时取回正文（第二层）；</li>
 *     <li>正文里提到的附带文件交给已有的 {@code read_file} / {@code shell} 工具（第三层）——
 *     本插件<b>不自己执行任何东西</b>，「读文件」这件事已经有工具了，重复一份只会多出第二条
 *     路径解析与权限口径。</li>
 * </ol>
 * <p>
 * <b>为什么是插件而不是内核能力</b>：skills 只是「发现一批说明文件，把最小信息给模型看」，
 * 没有碰循环的结构、也不需要内核才知道的事实（对比子代理：它要 {@code AgentManager} 现算
 * 可委派类型，插件拿不到）。放进内核会让不认识 skills 的用户也背上这段扫描代码。
 * <p>
 * <b>注册全部发生在 {@code start()} 内</b>：本插件不持有后台线程，也不在运行期改注册
 * ——清单变化是通过提示词贡献每轮现算体现的，因此它不需要「存活期内可继续注册」那条能力。
 * <p>
 * <b>配置段可选</b>：整段留空则用两个默认根目录（{@code ~/.jellyfish/skills} 与
 * {@code ./.jellyfish/skills}）与各项默认上限。
 *
 * @author zcd
 */
public final class SkillsPlugin implements JellyfishPlugin {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(SkillsPlugin.class);

    /** {@code /skills} 命令名。 */
    private static final String COMMAND_SKILLS = "skills";

    /**
     * 清单贡献的调用顺序。
     * <p>
     * 排在默认顺序（{@code 0}）的贡献之后：项目约定、待办这一类「当前状态」比「有哪些能力可用」
     * 更贴近这一轮要说的话，因此让它们先出现。
     */
    private static final int CONTRIBUTION_ORDER = 20;

    @Override
    public void start(PluginContext context) {
        // 先解析配置：值非法属配置错误，启动期就该让人看见，而不是压到第一次对话
        SkillsConfig config = SkillsConfig.from(context.configuration());
        SkillCatalog catalog = new SkillCatalog(config);
        // 台账命令无论启用与否都注册：禁用时「为什么什么都看不见」的唯一答案就在这里
        context.handle(CommandRequest.class, COMMAND_SKILLS,
                new CommandDescriptor("查看 skills 插件的根目录、已加载清单与扫描问题",
                        null, null, false),
                request -> CommandResult.ok(SkillsLedger.render(config, catalog)));
        if (!config.enabled()) {
            LOG.warn("skills 插件已禁用（enabled=false）：不注册 skill 工具与清单贡献，仅保留 /{} 台账",
                    COMMAND_SKILLS);
            return;
        }
        SkillTool tool = new SkillTool(catalog, config);
        context.handle(ToolCallRequest.class, SkillTool.NAME, tool.descriptor(), tool);
        context.contribute(PromptContributionRequest.class,
                new SkillPromptContribution(catalog, config), RegisterOptions.order(CONTRIBUTION_ORDER));
        LOG.info("skills 插件已启动: roots={} {}", config.roots(), catalog.current());
        List<String> issues = catalog.current().issues();
        for (String issue : issues) {
            LOG.error("skill 问题: {}", issue);
        }
    }
}
