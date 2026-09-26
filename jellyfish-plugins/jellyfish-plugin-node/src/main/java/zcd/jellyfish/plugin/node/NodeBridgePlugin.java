package zcd.jellyfish.plugin.node;

import zcd.jellyfish.script.ScriptBridgeConfig;
import zcd.jellyfish.script.ScriptBridgePlugin;
import zcd.jellyfish.script.ScriptLanguage;

import java.util.Map;

/**
 * Node 桥接插件：内核眼里的一个标准 PF4J 插件，背后是 Node 脚本插件。
 * <p>
 * <b>它薄成这样是有意的</b>：探测解释器、扫描清单、逐脚本注册、接通事件桥接与熔断、
 * 注册 {@code /node} 命令、按序关闭，这些都没有一处与 Node 有关，因此全在
 * {@link ScriptBridgePlugin} 里；本类只剩下「Node 的那几个事实」——
 * 解释器写在哪一个键上、默认值是什么、以及怎么造出语言适配。
 * <p>
 * <b>这也正是 P9 的验收</b>：新增一门语言没有让机制层（{@code jellyfish-script}）改动一行，
 * 也没有复制任何代理代码；上一门语言踩过的坑（能力上下文每次 start 现造、注册按命名空间回收、
 * 熔断不摘注册、PID 文件只报告）在第二门语言上自动成立。
 *
 * @author zcd
 */
public final class NodeBridgePlugin extends ScriptBridgePlugin {

    /**
     * 解释器配置键。
     * <p>
     * <b>为什么不统一叫 {@code interpreterPath}</b>：用户翻配置时找的是他那门语言的词
     * （Node 用户找 {@code nodePath}，Python 用户找 {@code pythonPath}），而统一命名只是
     * 为了省掉子类这一处参数，代价是每个人都要先学会一个新词。
     */
    static final String KEY_INTERPRETER = "nodePath";

    /** 默认解释器：交给 PATH 解析，不写死绝对路径（nvm/volta 这类版本管理器场景由用户用 {@code nodePath} 指定）。 */
    static final String DEFAULT_INTERPRETER = "node";

    /** 默认脚本根目录：相对进程工作目录，与内核「插件相对路径按进程 cwd 解析」同口径。 */
    static final String DEFAULT_SCRIPTS_ROOT = "scripts/node";

    @Override
    protected ScriptBridgeConfig resolveConfig(Map<String, Object> configuration) {
        return ScriptBridgeConfig.from(configuration, KEY_INTERPRETER, DEFAULT_INTERPRETER,
                DEFAULT_SCRIPTS_ROOT);
    }

    @Override
    protected ScriptLanguage createLanguage(ScriptBridgeConfig config) {
        return new NodeLanguage(config.interpreterPath());
    }
}
