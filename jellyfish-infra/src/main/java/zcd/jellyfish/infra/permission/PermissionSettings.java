package zcd.jellyfish.infra.permission;

/**
 * 权限相关的用户可见配置键。
 * <p>
 * 这些键写在 {@code jellyfish.json} 的 {@code plugins.<pluginId>} 配置段里，随插件配置一起双源合并，
 * 再由 {@link ReadOnlyTools} 解析。集中声明键名，避免字符串散落在解析代码里。
 *
 * @author zcd
 */
public final class PermissionSettings {

    /**
     * 只读工具白名单键，写在插件自己那一节配置里。
     * <pre>
     * {
     *   "plugins": {
     *     "my-plugin": { "readOnlyTools": ["read_file", "list_dir"] }
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
