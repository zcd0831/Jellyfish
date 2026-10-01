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
 * 注意：工具的只读性<b>完全由这里的配置决定</b>。早先还有「工具提供方在描述符里声明只读」这一来源，
 * 它与本配置取并集，导致白名单只增不减且判定权落在提供方手里，因此已移除。
 *
 * @author zcd
 */
public final class PermissionSettings {

    /**
     * 只读工具白名单键，写在插件自己那一节配置里。
     * <p>
     * PLAN 模式下可用的工具<b>就是这里列出的那些</b>：没有第二份来源，工具提供方也无法自称只读
     * （工具描述符里已没有该字段）。取值是工具名，与「工具名全局唯一」的既有约定一致。
     * <p>
     * <b>不写就等于 PLAN 下全部不可用</b>（白名单语义），这是刻意的。
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
