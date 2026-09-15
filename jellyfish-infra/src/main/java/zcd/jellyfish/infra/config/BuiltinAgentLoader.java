package zcd.jellyfish.infra.config;

import org.apache.commons.lang3.StringUtils;
import zcd.jellyfish.api.JellyfishException;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Objects;

/**
 * 内置默认 agent 的加载器：读 {@code classpath:default-agent.json}，并补上同目录的
 * {@code classpath:{agentId}.md} 提示词。
 * <p>
 * 与用户自定义 agent（{@code agents.json}，走双源合并）不同，这份定义<b>随构件发布、用户改不了</b>：
 * 它就是「不配置任何 agent 也能正常对话」的那份兜底，因此启动时永远绑它，
 * 想用自定义 agent 必须显式切换。
 * <p>
 * <b>失败语义与用户 agent 相反</b>：用户 agent 的 md 缺失只算「这个 agent 没有提示词」，切过去时告警；
 * 而内置定义或它的 md 缺失是<b>打包错误</b>——此时没有任何可用的默认 agent，
 * 继续启动只会得到一个静默退化的内核，因此这里直接抛 {@link JellyfishException}。
 * <p>
 * 结果缓存在实例字段：内置资源不参与配置热更新，进程生命周期内不变。
 *
 * @author zcd
 */
@Singleton
public class BuiltinAgentLoader {

    /** 内置默认 agent 定义的固定路径，与 {@code config.json} 同处 classpath 根。 */
    public static final String DEFAULT_AGENT_PATH = "classpath:default-agent.json";

    /** 配置读取门面，负责把 JSON 绑定成 {@link AgentDefinition}。 */
    private final ConfigLoader configLoader;

    /** 提示词加载器，负责读同目录的 {@code {agentId}.md}。 */
    private final AgentPromptLoader promptLoader;

    /** 加载结果缓存，使用双重检查以兼顾并发与只读一次。 */
    private volatile AgentDefinition cached;

    /**
     * 构造内置 agent 加载器。
     *
     * @param configLoader  配置读取门面
     * @param promptLoader  提示词加载器
     */
    @Inject
    public BuiltinAgentLoader(ConfigLoader configLoader, AgentPromptLoader promptLoader) {
        this.configLoader = Objects.requireNonNull(configLoader, "configLoader must not be null");
        this.promptLoader = Objects.requireNonNull(promptLoader, "promptLoader must not be null");
    }

    /**
     * 加载内置默认 agent。
     *
     * @return 内置默认 agent 定义，保证非 {@code null} 且已带上提示词
     * @throws JellyfishException 定义缺失、agentId 非法或提示词文件缺失时抛出
     */
    public AgentDefinition load() {
        AgentDefinition loaded = cached;
        if (loaded != null) {
            return loaded;
        }
        synchronized (this) {
            if (cached == null) {
                cached = read();
            }
            return cached;
        }
    }

    /**
     * 真正读取定义与提示词。
     *
     * @return 内置默认 agent 定义
     */
    private AgentDefinition read() {
        AgentDefinition definition = configLoader.read(DEFAULT_AGENT_PATH, AgentDefinition.class);
        if (definition == null) {
            throw new JellyfishException("内置默认 agent 配置缺失: " + DEFAULT_AGENT_PATH);
        }
        String agentId = definition.getAgentId();
        if (!promptLoader.isSafeAgentId(agentId)) {
            throw new JellyfishException("内置默认 agent 的 agentId 非法: " + agentId);
        }
        String prompt = promptLoader.load(AgentPromptLoader.CLASSPATH_ROOT, agentId);
        if (StringUtils.isEmpty(prompt)) {
            throw new JellyfishException("内置默认 agent 提示词缺失: " + agentId + ".md");
        }
        return definition.withSystemPrompt(prompt);
    }
}
