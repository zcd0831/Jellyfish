package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code classpath:config.json} 的 {@code plugins} 段：插件扫描根目录。
 * <p>
 * 与 {@link ConfigPaths} 并列的应用级配置：{@code ConfigPaths} 描述「配置<b>文件</b>在哪」，
 * 本类描述「插件<b>jar</b>在哪」。两者都只写在 {@code config.json} 里，且都不参与双源合并——
 * 一个是单文件定位，一个是目录清单。
 * <p>
 * 为什么放在 {@code config.json} 而不是 {@code jellyfish.json}：扫描目录属于「内核去哪找能力」的
 * 部署事实，与 {@code models.json} / {@code agents.json} 的路径同一性质，应当和它们放在一起；
 * {@code jellyfish.json} 的 {@code plugins} 段只保留「加载后怎么用」的运行期设置
 * （{@code enabled} / {@code disabled} / {@code configurations}）。
 * <p>
 * 不可变：列表在构造时复制并包装，缺省为空列表而非 {@code null}。空表示未配置，
 * 由 {@code PluginRuntimeConfig} 回退到默认扫描目录。
 *
 * @author zcd
 */
public class PluginPaths {

    /** 插件扫描根目录，顺序即扫描顺序。 */
    private final List<String> roots;

    /**
     * 反序列化使用的构造器，由 {@link JsonCreator} 接管。
     *
     * @param roots 插件扫描根目录，可为 {@code null}
     */
    @JsonCreator
    public PluginPaths(@JsonProperty("roots") List<String> roots) {
        this.roots = roots == null || roots.isEmpty()
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(roots));
    }

    /**
     * 获取插件扫描根目录。
     *
     * @return 不可修改列表，未配置时为空列表而非 {@code null}
     */
    public List<String> getRoots() {
        return roots;
    }
}
