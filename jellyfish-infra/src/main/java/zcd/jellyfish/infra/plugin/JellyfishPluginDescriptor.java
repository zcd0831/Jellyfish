package zcd.jellyfish.infra.plugin;

import org.pf4j.DefaultPluginDescriptor;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginWrapper;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.plugin.PluginDeclaration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 插件描述符：在 PF4J 原生描述符之上补充标签与描述符层面的问题。
 * <p>
 * 三份额外信息：
 * <ul>
 *     <li>{@code tags}：供按标签授权使用；</li>
 *     <li>{@code loadErrors}：描述符层面发现的问题（如缺少 {@code plugin.class}）。</li>
 * </ul>
 * <b>描述符层面发现的问题一律记入 {@link #getLoadErrors()}，不抛异常</b>：PF4J 的加载循环只捕获
 * {@code PluginRuntimeException}，在描述符解析里抛任何其他异常都会中断整批加载，把「一个坏插件」
 * 放大成「全部插件都没了」。
 * <p>
 * 解析期由 {@link JellyfishPluginDescriptorFinder} 通过包级方法填充，解析完成后只读。
 *
 * @author zcd
 */
public final class JellyfishPluginDescriptor extends DefaultPluginDescriptor {

    /** 缺少版本时的兜底版本号，避免下游依赖解析拿到 {@code null}。 */
    static final String FALLBACK_VERSION = "0.0.0";

    /** 插件标签。 */
    private final Set<String> tags = new LinkedHashSet<>();

    /** 描述符层面的问题，非空表示该插件不应启动。 */
    private final List<String> loadErrors = new ArrayList<>();

    /**
     * 从插件包装器取出本项目的描述符。
     * <p>
     * 解析器由管理器指定，正常路径下描述符必然是 {@link JellyfishPluginDescriptor}；
     * 类型不符只可能是管理器被替换过，因此直接报错而不是降级。
     *
     * @param wrapper 插件包装器，不可为 {@code null}
     * @return 插件描述符
     * @throws JellyfishException 描述符类型不符时抛出
     */
    static JellyfishPluginDescriptor of(PluginWrapper wrapper) {
        PluginDescriptor descriptor = wrapper.getDescriptor();
        if (descriptor instanceof JellyfishPluginDescriptor) {
            return (JellyfishPluginDescriptor) descriptor;
        }
        throw new JellyfishException("unexpected plugin descriptor type: " + descriptor.getClass().getName());
    }

    /**
     * 获取插件标签。
     *
     * @return 不可变集合，未声明时为空集
     */
    public Set<String> getTags() {
        return Collections.unmodifiableSet(tags);
    }

    /**
     * 获取描述符层面的问题。
     *
     * @return 不可变列表，无问题时为空列表
     */
    public List<String> getLoadErrors() {
        return Collections.unmodifiableList(loadErrors);
    }

    /**
     * 判断是否存在描述符层面的问题。
     *
     * @return 存在问题返回 {@code true}
     */
    public boolean hasLoadErrors() {
        return !loadErrors.isEmpty();
    }

    /**
     * 转换为插件声明。
     *
     * @param configuration 插件配置段，可为 {@code null}
     * @return 插件声明
     */
    public PluginDeclaration toDeclaration(Map<String, Object> configuration) {
        return PluginDeclaration.of(getPluginId(), configuration);
    }

    /**
     * 追加标签，逗号分隔。
     *
     * @param csv 逗号分隔的标签，可为 {@code null}
     */
    void addTags(String csv) {
        addCsv(tags, csv);
    }

    /**
     * 记录一条描述符层面的问题。
     *
     * @param error 问题描述
     */
    void addLoadError(String error) {
        loadErrors.add(error);
    }

    /**
     * 覆盖插件版本。
     * <p>
     * 父类的 setter 是 {@code protected}，只有子类可直接调用，故包级转发给解析器。
     *
     * @param version 覆盖后的版本号
     */
    void overrideVersion(String version) {
        setPluginVersion(version);
    }

    /**
     * 覆盖内核兼容版本约束。
     *
     * @param requires 覆盖后的约束表达式
     */
    void overrideRequires(String requires) {
        setRequires(requires);
    }

    /**
     * 解析逗号分隔列表并去重去空。
     *
     * @param target 收集目标
     * @param csv    逗号分隔文本，可为 {@code null}
     */
    private static void addCsv(Set<String> target, String csv) {
        if (csv == null) {
            return;
        }
        for (String item : csv.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                target.add(trimmed);
            }
        }
    }
}
