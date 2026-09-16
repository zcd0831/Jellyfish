package zcd.jellyfish.infra.permission;

/**
 * 权限相关的用户可见配置键。
 * <p>
 * 这些键写在 {@code jellyfish.json} 的 {@code plugins.configurations.<pluginId>} 配置段里
 * （{@code configurations} 与 {@code enabled} / {@code disabled} 两个保留键并列，
 * 避免插件标识与保留键撞名；扫描目录不在此文件，而在 {@code config.json} 的 {@code plugins.roots}），
 * 随插件配置一起双源合并，再由 {@link ReadOnlyTools} 解析。
 * 集中声明键名，避免字符串散落在解析代码里。
 * <p>
 * 注意：工具的只读性<b>默认由工具提供方在描述符里声明</b>，这里的配置是<b>用户追加</b>，
 * 用于把提供方没标只读的工具自行纳入 PLAN 白名单。
 *
 * @author zcd
 */
public final class PermissionSettings {

    /**
     * 只读工具白名单键，写在插件自己那一节配置里。
     * <p>
     * 是<b>追加</b>而非替代：工具提供方若已在 {@code ToolDescriptor} 里声明只读，这里可以不写。
     * <pre>
     * {
     *   "plugins": {
     *     "configurations": {
     *       "my-plugin": { "readOnlyTools": ["read_file", "list_dir"] }
     *     }
     *   }
     * }
     * </pre>
     */
    public static final String READ_ONLY_TOOLS = "readOnlyTools";

    /**
     * 常量类，禁止实例化。
     */
    private PermissionSettings() {
    }
}
