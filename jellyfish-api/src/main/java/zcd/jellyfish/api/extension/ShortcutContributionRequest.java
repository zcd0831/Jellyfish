package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.RuntimeInfo;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 快捷键贡献请求：外壳收集键位表时，问插件「你想占用哪些键」。
 * <p>
 * <b>注册方式</b>：{@code contribute} + {@code order} 升序。多个插件可以共存，
 * 抢同一个键时 <b>{@code order} 最小者胜</b>，被挤掉的进 {@code /ui} 的诊断清单
 * （与面板落位的仲裁口径一致）。
 * <p>
 * <b>0 个处理器时</b>：没有任何插件键位，键盘行为与没有这个扩展点时逐字段一致。
 * <p>
 * <b>失败语义</b>：处理器抛错 → 只记 WARN 并跳过它自己，其余插件照常出键位。
 * <p>
 * <b>请求里给的是「内核保留键位」，不是「别人已经占了哪些」</b>：收集是<b>一趟</b>完成的，
 * 在问某个插件时，其它插件的声明还没收齐，因此「已被占用」在那时不是一个能回答的问题；
 * 而插件之间的冲突本来就由内核按 {@code order} 确定性仲裁，不需要插件自己避让。
 * 保留键位不同：它是一份固定的内核事实（退出 / 发送 / 展开思考 / 展开参数 / 交还鼠标），
 * 插件据此前置判断才是有效的——因此它连常量都放在
 * {@link ShortcutBinding#RESERVED_KEYS}，插件自己也能查。
 * <p>
 * <b>实现约定</b>：由外壳在<b>渲染线程</b>内联调用（收集是「失效时才做」的，不在每帧路径上），
 * 处理器必须快且只读。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ShortcutContributionRequest extends ExtensionRequest<ShortcutContribution> {

    /** 外壳运行时信息：按键只有交互式界面才有意义，无界面的外壳应当直接不表态。 */
    private final RuntimeInfo shell;

    /** 内核保留、插件不可占用的键位。 */
    private final Set<String> reservedKeys;

    /**
     * 构造请求。
     *
     * @param shell 外壳运行时信息，可为 {@code null}（按 {@link RuntimeInfo#unknown()} 理解）
     */
    public ShortcutContributionRequest(RuntimeInfo shell) {
        super(ShortcutContribution.class, null);
        this.shell = shell == null ? RuntimeInfo.unknown() : shell;
        this.reservedKeys = new LinkedHashSet<String>(ShortcutBinding.RESERVED_KEYS);
    }

    @Override
    public String getRouteKey() {
        // 类型级扩展点：多个插件各自占键，冲突由内核按 order 仲裁
        return null;
    }

    /**
     * 获取外壳运行时信息。
     *
     * @return 外壳运行时信息，保证非 {@code null}
     */
    public RuntimeInfo getShell() {
        return shell;
    }

    /**
     * 获取内核保留的键位。
     *
     * @return 不可修改的键位集合，可能为空但不会为 {@code null}
     */
    public Set<String> getReservedKeys() {
        return Collections.unmodifiableSet(reservedKeys);
    }
}
