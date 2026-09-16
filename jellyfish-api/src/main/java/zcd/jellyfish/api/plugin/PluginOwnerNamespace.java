package zcd.jellyfish.api.plugin;

/**
 * 插件 owner 命名空间：注册表里「一个插件可以用多个来源」这件事的约定。
 * <p>
 * <b>解决什么问题</b>：注册表的每一笔登记都带一个 {@code owner}，框架按 {@code owner} 批量回收。
 * 插件若想给内部子单元（脚本插件的每个脚本、多后端插件的每个后端……）分独立的来源，
 * 以获得可归因的诊断与更细的粒度，就必须让子来源既是「自己的」，又能被「插件的」那一次回收收干净。
 * 做法是把子来源放在插件标识的命名空间下：
 * <pre>
 *   owner = pluginId                       // 插件自身的登记
 *   owner = pluginId + SEPARATOR + 子标识   // 子单元的登记，例如 jellyfish-plugin-python::jira
 * </pre>
 * 框架按命名空间回收时，{@code pluginId} 自身与 {@code pluginId}{@code ::*} 一并清除，
 * 不留「插件已停、工具还能调」的幽灵注册。
 * <p>
 * <b>为什么常量放在 api 而不是内核</b>：分隔符是<b>跨边界契约</b>——插件用它拼来源，
 * 内核用它做前缀回收，两侧必须字面一致。只写在内核里，插件就只能自己抄一份字面量，
 * 一旦分隔符变动，表现是回收越界或残留，而现场看不出任何异常。
 * <p>
 * <b>分隔符为什么是 {@code ::}</b>：PF4J 的 {@code plugin.id} 不接受它，因此不存在
 * 「某个插件恰好叫 {@code x::y}」的歧义。内核在描述符体检阶段会拒绝含本分隔符的 {@code plugin.id}——
 * 否则那样的插件会把自己的登记挂进命名空间 {@code x}，{@code x} 停止时就会越界抹掉它。
 *
 * @author zcd
 */
public final class PluginOwnerNamespace {

    /**
     * 命名空间分隔符。
     * <p>
     * 组合方式为 {@code pluginId + SEPARATOR + 子标识}；层级可以更深（{@code a::b::c}），
     * 回收 {@code a} 时一并命中。
     */
    public static final String SEPARATOR = "::";

    /**
     * 常量类，禁止实例化。
     */
    private PluginOwnerNamespace() {
    }
}
