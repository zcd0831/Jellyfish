package zcd.jellyfish.plugin.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.extension.PromptContributionRequest;
import zcd.jellyfish.api.plugin.JellyfishPlugin;
import zcd.jellyfish.api.plugin.PluginContext;

/**
 * 官方项目约定插件：让模型知道工作目录下有 {@code AGENTS.md}，并在动手前读它。
 * <p>
 * <b>一个插件只占一个扩展点</b>：{@link PromptContributionRequest}。没有工具、没有命令、没有界面贡献——
 * 「读文件」这件事已经有 {@code jellyfish-plugin-tools} 的 {@code read_file} 了，本插件只负责
 * <b>告诉模型该去读哪个文件</b>。
 * <p>
 * <b>为什么是插件而不是内置提示词</b>：项目约定属于「项目」而不属于「某个 agent」。
 * 写进 {@code jellyfish.md} 只能覆盖内置默认 agent，用户一旦用 {@code /agent} 换成自定义 agent 就失效；
 * 走插件则对所有 agent 生效，内核也不必开始处理「读工作目录里的文件」这类它本不该管的事。
 * <p>
 * <b>没有配置项</b>：文件名固定为 {@link ConventionFiles#CONVENTION_FILE}，查找基准固定为进程工作目录。
 * 配置段留空时插件照常工作（{@code PluginContext.configuration()} 会给出空映射）。
 *
 * @author zcd
 */
public final class ProjectPlugin implements JellyfishPlugin {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ProjectPlugin.class);

    @Override
    public void start(PluginContext context) {
        // 先装配再注册：处理器一旦注册就可能被调用，依赖必须已经就绪
        ConventionFiles files = ConventionFiles.ofWorkingDirectory();
        context.contribute(PromptContributionRequest.class, new ProjectPromptContribution(files));
        LOG.info("项目约定插件已启动: 查找基准={}", files.baseDirectory());
    }
}
