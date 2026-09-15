package zcd.jellyfish.infra.config;

import org.apache.commons.lang3.StringUtils;
import zcd.jellyfish.infra.support.HomePaths;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * agent 提示词文件（{@code {agentId}.md}）的加载器。
 * <p>
 * agent 的系统提示词不再写在配置 JSON 里，而是与 {@code agents.json}（或内置的
 * {@code default-agent.json}）同目录的一个 Markdown 文件，文件名即 agentId。这样提示词可以写成
 * 多段长文而不必挤进一行 JSON 字符串。
 * <p>
 * <b>agentId 现在是文件名，必须按不可信输入校验</b>：它来自配置文件的映射 key，而 key 可以包含
 * 路径分隔符或 {@code ..}。这里用 {@link #isSafeAgentId(String)} 做白名单式排除，
 * 拒绝读文件的条目由调用方决定（见 {@code RuntimeConfig}：整条 agent 被丢弃并告警），
 * 不在本类里静默降级——「读不到提示词」与「这个 key 根本不该当文件名」是两件事。
 * <p>
 * <b>基准目录由调用方给出</b>：本地目录用普通路径，classpath 根用 {@link #CLASSPATH_ROOT}
 * （即 {@code classpath:} 本身）。{@link #promptBaseOf(String)} 负责从配置文件路径推导出错基准，
 * 保证「提示词与配置文件同目录」这条约定只有一处实现。
 *
 * @author zcd
 */
@Singleton
public class AgentPromptLoader {

    /** classpath 资源前缀，同时表示「classpath 根目录」这一基准。 */
    public static final String CLASSPATH_ROOT = "classpath:";

    /** 提示词文件后缀。 */
    private static final String PROMPT_SUFFIX = ".md";

    /** 不允许出现在文件名中的字符：路径分隔符与 Windows 保留字符。 */
    private static final String ILLEGAL_CHARS = "/\\:*?\"<>|";

    /** 只读配置读取器，负责「路径 → 文本」。 */
    private final SettingsReader settingsReader;

    /**
     * 构造提示词加载器。
     *
     * @param settingsReader 只读配置读取器
     */
    @Inject
    public AgentPromptLoader(SettingsReader settingsReader) {
        this.settingsReader = Objects.requireNonNull(settingsReader, "settingsReader must not be null");
    }

    /**
     * 判断 agentId 能否安全地作为文件名。
     * <p>
     * 拒绝空白、以 {@code .} 开头、包含 {@code ..} 或路径分隔符 / Windows 保留字符的标识。
     * 不限制字符集本身，因此中文 agent 名仍然可用。
     *
     * @param agentId agent 标识，可为 {@code null}
     * @return 可以安全拼接为文件名时返回 {@code true}
     */
    public boolean isSafeAgentId(String agentId) {
        if (StringUtils.isBlank(agentId) || agentId.startsWith(".") || agentId.contains("..")) {
            return false;
        }
        for (int i = 0; i < ILLEGAL_CHARS.length(); i++) {
            if (agentId.indexOf(ILLEGAL_CHARS.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 读取指定 agent 的提示词。
     *
     * @param base    基准目录：本地目录路径，或 {@link #CLASSPATH_ROOT} 表示 classpath 根
     * @param agentId agent 标识，不可为非法文件名
     * @return 提示词文本；基准为空、标识非法或文件不存在时返回 {@code null}
     */
    public String load(String base, String agentId) {
        if (StringUtils.isBlank(base) || !isSafeAgentId(agentId)) {
            return null;
        }
        return settingsReader.read(promptPath(base, agentId));
    }

    /**
     * 由配置文件路径推导提示词基准目录。
     * <p>
     * {@code classpath:xxx} 一律回落到 classpath 根（内置资源与配置文件都在包根）；
     * 本地路径取其父目录；文件没有父目录（相对单层路径）时返回 {@code null}，表示无法定位提示词。
     *
     * @param configPath 配置文件的完整路径，可为 {@code null}
     * @return 基准目录；无法推导时返回 {@code null}
     */
    public String promptBaseOf(String configPath) {
        if (StringUtils.isBlank(configPath)) {
            return null;
        }
        if (configPath.startsWith(CLASSPATH_ROOT)) {
            return CLASSPATH_ROOT;
        }
        Path parent = Paths.get(HomePaths.expand(configPath)).getParent();
        return parent == null ? null : parent.toString();
    }

    /**
     * 拼接提示词文件的完整路径。
     *
     * @param base    基准目录或 {@link #CLASSPATH_ROOT}
     * @param agentId agent 标识
     * @return 完整路径
     */
    private static String promptPath(String base, String agentId) {
        if (CLASSPATH_ROOT.equals(base)) {
            return CLASSPATH_ROOT + agentId + PROMPT_SUFFIX;
        }
        return Paths.get(base).resolve(agentId + PROMPT_SUFFIX).toString();
    }
}
