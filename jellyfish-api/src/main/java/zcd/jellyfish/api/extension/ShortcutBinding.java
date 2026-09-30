package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 一条快捷键绑定：按哪个键、执行哪条命令、帮助里怎么描述。
 * <p>
 * <b>键位形状被收窄成 {@code ctrl+[a-z]}，这是终端事实而不是设计洁癖</b>：TamboUI 的键盘解码器
 * 不解析任何修饰键编码——{@code Shift+Enter}、CSI-u、CSI-27 一律落成 {@code UNKNOWN}，
 * {@code hasShift()} / {@code hasAlt()} 永远是 {@code false}。真正能按码点比较的只有
 * {@code Ctrl+字母}（实测 {@code Ctrl+C} 得到码点 {@code 99}）。放开 {@code shift+a}、{@code ctrl+1}
 * 这类形状，插件会在自己的开发机上看起来能用、在别人的终端上静默失效，而那种问题没人查得出来。
 * 因此<b>非法形状在构造时当场报错</b>（编程错误，不是运行时条件），而不是收下之后不生效。
 * <p>
 * <b>它派发的是一条 {@code /命令}，不是回调</b>：{@code commandName} 必须是一条已注册命令的名字。
 * 这样插件不需要「被内核回调」这个新能力（{@code JellyfishPlugin} 仍然是「不能发起回调」），
 * 而命令域已有的审计（{@code CommandExecutedEvent}）、{@code sessionRequired} 判定与错误处理全部复用。
 * 快捷键的可发现性也顺带解决——{@code /help} 里本来就有这条命令。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ShortcutBinding {

    /**
     * 内核保留、插件不可占用的键位。
     * <p>
     * 每条都对应一个已有的外壳动作：退出、发送、展开思考、展开参数、交还鼠标。占用它们不是
     * 「优先级」问题——用户对这几个键的预期已经完全固定，让插件改写会把最基本的操作变成需要学习的东西。
     * <p>
     * <b>常量放在 {@code api}</b>：插件侧自查与框架侧强制必须是同一个真源。
     */
    public static final Set<String> RESERVED_KEYS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList("ctrl+c", "ctrl+s", "ctrl+t", "ctrl+e", "ctrl+o")));

    /** 合法键位的形状：{@code ctrl+} 加一个小写字母。 */
    // 注意转义：{@code ctrl+} 里的加号是字面量，不转义就变成「一个或多个 l」
    private static final Pattern KEY_SHAPE = Pattern.compile("ctrl\\+[a-z]");

    /** 键位，已规范化为小写。 */
    private final String key;

    /** 目标命令名（不含前缀斜杠）。 */
    private final String commandName;

    /** 描述，用于 {@code /help} 与 {@code /ui} 的诊断输出。 */
    private final String description;

    /**
     * 构造绑定。
     *
     * @param key         键位，形状必须是 {@code ctrl+[a-z]}，忽略大小写与首尾空白
     * @param commandName 目标命令名（不含前缀斜杠），不可为空白
     * @param description 描述，可为 {@code null}
     * @throws JellyfishException 键位形状非法或命令名为空白时抛出
     */
    public ShortcutBinding(String key, String commandName, String description) {
        if (commandName == null || commandName.trim().isEmpty()) {
            throw new JellyfishException("shortcut binding commandName must not be blank");
        }
        this.key = normalize(key);
        this.commandName = commandName.trim();
        this.description = description;
    }

    /**
     * 规范化并校验键位。
     *
     * @param key 原始键位
     * @return 规范化后的键位
     * @throws JellyfishException 形状非法时抛出
     */
    public static String normalize(String key) {
        String normalized = key == null ? "" : key.trim().toLowerCase(java.util.Locale.ROOT);
        if (!KEY_SHAPE.matcher(normalized).matches()) {
            throw new JellyfishException("shortcut key must look like ctrl+a (Ctrl+<letter> only, "
                    + "因为终端不解码其它修饰键): " + key);
        }
        return normalized;
    }

    /**
     * 判断键位是否合法，供插件侧自查而不必捕获异常。
     *
     * @param key 键位
     * @return 合法返回 {@code true}
     */
    public static boolean isValidKey(String key) {
        return key != null && KEY_SHAPE.matcher(key.trim().toLowerCase(java.util.Locale.ROOT)).matches();
    }

    /**
     * 判断键位是否为内核保留键位。
     *
     * @param key 键位
     * @return 保留返回 {@code true}
     */
    public static boolean isReserved(String key) {
        return key != null && RESERVED_KEYS.contains(key.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * 获取键位。
     *
     * @return 规范化后的键位，形如 {@code ctrl+a}
     */
    public String getKey() {
        return key;
    }

    /**
     * 获取目标命令名。
     *
     * @return 命令名，不含前缀斜杠
     */
    public String getCommandName() {
        return commandName;
    }

    /**
     * 获取描述。
     *
     * @return 描述，可能为 {@code null}
     */
    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return "ShortcutBinding{" + key + " -> /" + commandName + '}';
    }
}
