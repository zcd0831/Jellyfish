package zcd.jellyfish.infra.config;

/**
 * 一类配置的「全局级 + 项目级」双源文件路径对。
 * <p>
 * 值均为完整文件路径（可带 {@code classpath:} 前缀），由 {@code classpath:config.json} 中的同名配置段提供。
 * 本类只描述路径，不参与任何合并；两个层级如何覆盖由 {@link RuntimeConfig} 的合并策略决定。
 * 空串表示该层级未配置。
 *
 * @author zcd
 */
public class ConfigPaths {

    /** 全局级配置文件完整路径，空串表示未配置。 */
    private String globalPath = "";

    /** 项目级配置文件完整路径，空串表示未配置。 */
    private String projectPath = "";

    /**
     * 获取全局级配置文件路径。
     *
     * @return 全局级配置文件路径
     */
    public String getGlobalPath() {
        return globalPath;
    }

    /**
     * 设置全局级配置文件路径。
     *
     * @param globalPath 全局级配置文件路径
     */
    public void setGlobalPath(String globalPath) {
        this.globalPath = globalPath;
    }

    /**
     * 获取项目级配置文件路径。
     *
     * @return 项目级配置文件路径
     */
    public String getProjectPath() {
        return projectPath;
    }

    /**
     * 设置项目级配置文件路径。
     *
     * @param projectPath 项目级配置文件路径
     */
    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
    }
}
