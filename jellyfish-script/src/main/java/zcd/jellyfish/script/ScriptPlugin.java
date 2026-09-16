package zcd.jellyfish.script;

import java.nio.file.Path;

/**
 * 一个已加载的脚本插件：清单 + 它所在的目录。
 * <p>
 * 扫描产出的就是它，注册期按它的清单逐条注册转发处理器，运行时按它的目录找到入口文件。
 * 它<b>不含 owner</b>：owner 是「桥接插件 pluginId + 本脚本 id」拼出来的，只有桥接插件知道
 * 自己的 pluginId，因此拼接留在那一侧。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ScriptPlugin {

    /** 脚本标识。 */
    private final String id;

    /** 脚本目录（绝对路径）。 */
    private final Path directory;

    /** 清单。 */
    private final ScriptManifest manifest;

    /**
     * 构造脚本插件。
     *
     * @param id        脚本标识，不可为空白
     * @param directory 脚本目录，不可为 {@code null}
     * @param manifest  清单，不可为 {@code null}
     */
    public ScriptPlugin(String id, Path directory, ScriptManifest manifest) {
        this.id = id;
        this.directory = directory;
        this.manifest = manifest;
    }

    /**
     * 获取脚本标识。
     *
     * @return 脚本标识
     */
    public String id() {
        return id;
    }

    /**
     * 获取脚本目录。
     *
     * @return 脚本目录
     */
    public Path directory() {
        return directory;
    }

    /**
     * 获取清单。
     *
     * @return 清单
     */
    public ScriptManifest manifest() {
        return manifest;
    }

    /**
     * 获取入口文件的绝对路径。
     *
     * @return 入口文件路径
     */
    public Path entryFile() {
        return directory.resolve(manifest.entry());
    }

    @Override
    public String toString() {
        return "ScriptPlugin{id=" + id + ", directory=" + directory + '}';
    }
}
