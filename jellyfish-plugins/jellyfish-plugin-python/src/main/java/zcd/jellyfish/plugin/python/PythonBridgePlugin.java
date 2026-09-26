package zcd.jellyfish.plugin.python;

import zcd.jellyfish.script.ScriptBridgeConfig;
import zcd.jellyfish.script.ScriptBridgePlugin;
import zcd.jellyfish.script.ScriptLanguage;

import java.util.Map;

/**
 * Python 桥接插件：内核眼里的一个标准 PF4J 插件，背后是 Python 脚本插件。
 * <p>
 * <b>它薄成这样是有意的</b>：探测解释器、扫描清单、逐脚本注册、接通事件桥接与熔断、
 * 注册 {@code /python} 命令、按序关闭，这些都没有一处与 Python 有关，因此全在
 * {@link ScriptBridgePlugin} 里；本类只剩下「Python 的那几个事实」——
 * 解释器写在哪一个键上、默认值是什么、以及怎么造出语言适配。
 * <p>
 * 判据很简单：<b>要在这里写第二件事，先问它是不是语言无关的</b>。
 * 是，就该往上收；不是，才留下。
 *
 * @author zcd
 */
public final class PythonBridgePlugin extends ScriptBridgePlugin {

    /**
     * 解释器配置键。
     * <p>
     * <b>为什么不统一叫 {@code interpreterPath}</b>：用户翻配置时找的是他那门语言的词
     * （Python 用户找 {@code pythonPath}，Node 用户找 {@code nodePath}），而统一命名只是
     * 为了省掉子类这一处参数，代价是每个人都要先学会一个新词。
     */
    static final String KEY_INTERPRETER = "pythonPath";

    /** 默认解释器：交给 PATH 解析，不写死绝对路径（虚拟环境场景由用户用 {@code pythonPath} 指定）。 */
    static final String DEFAULT_INTERPRETER = "python3";

    /** 默认脚本根目录：相对进程工作目录，与内核「插件相对路径按进程 cwd 解析」同口径。 */
    static final String DEFAULT_SCRIPTS_ROOT = "scripts/python";

    @Override
    protected ScriptBridgeConfig resolveConfig(Map<String, Object> configuration) {
        return ScriptBridgeConfig.from(configuration, KEY_INTERPRETER, DEFAULT_INTERPRETER,
                DEFAULT_SCRIPTS_ROOT);
    }

    @Override
    protected ScriptLanguage createLanguage(ScriptBridgeConfig config) {
        return new PythonLanguage(config.interpreterPath());
    }
}
