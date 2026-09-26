package zcd.jellyfish.script;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 脚本语言适配：描述「一门语言怎么被宿主」。
 * <p>
 * 这是新增语言时唯一需要实现的东西。跨语言运行时（协议、进程管理、事件桥接、熔断）对语言一无所知，
 * 它只通过本接口问三件事：怎么启动、启动前怎么探测、进程该带什么环境。
 * <p>
 * <b>实现方是桥接插件</b>（见 {@code jellyfish-plugin-python} 的 {@code PythonLanguage}）：
 * 它把该语言的解释器路径、命令行形态与资源位置填进来，而本接口本身留在语言无关的运行时里。
 * 因此「新增一种语言 = 新增一个极薄的桥接插件 + 一份语言适配 + 一份该语言的常驻网关资源」，
 * 内核与运行时都不动。
 * <p>
 * <b>为什么探测与启动分开</b>：探测在插件 {@code start()} 期执行，只回答「这台机器上有没有这个解释器」，
 * 失败属于环境问题、只记告警，不能让插件转 {@code FAILED}——否则用户看到的是「工具凭空消失」。
 * 启动则在首次真正调用时才发生（懒启动），失败只让那一次调用失败。
 * <p>
 * 实现类必须是不可变的，可安全跨线程传递。
 *
 * @author zcd
 */
public interface ScriptLanguage {

    /**
     * 获取语言标识。
     * <p>
     * 用作配置键后缀、进程标识与日志归属（如 {@code python}），必须稳定且不含空白。
     *
     * @return 语言标识
     */
    String id();

    /**
     * 获取给人看的语言名，仅用于日志与状态展示。
     *
     * @return 语言名
     */
    String displayName();

    /**
     * 获取解释器探测命令。
     * <p>
     * 应当是一条<b>快速且无副作用</b>的命令（如 {@code python3 --version}）：
     * 它只用来判断解释器是否存在与可否执行，不加载任何脚本、不产生任何输出要求。
     *
     * @return 命令与参数列表，保证非 {@code null} 且非空
     */
    List<String> probeCommand();

    /**
     * 获取常驻网关的资源名。
     * <p>
     * 返回的是<b>类路径上的名字</b>（如 {@code script/gateway.py}）：网关代码在桥接插件 jar 内，
     * 由 {@code ScriptLifecycle} 按这些名字抽取到磁盘后才可执行。
     * <p>
     * <b>为什么把资源清单归语言适配</b>：它描述的是「这门语言的网关由哪几个文件组成」，
     * 与解释器路径、启动命令同属「这门语言长什么样」。把它留给调用方填，会把同一份知识
     * 复制到每一个需要构造网关的地方，而两处不一致的表现是「网关少了一个文件」——
     * 那类错误只在生产里、只在第一次调用时出现。
     *
     * @return 资源名列表，保证非 {@code null} 且非空
     */
    List<String> gatewayResources();

    /**
     * 获取常驻网关进程的启动命令。
     * <p>
     * 命令里出现的路径必须是<b>已经落盘的绝对路径</b>：网关资源在插件 jar 内，
     * 由 {@code ScriptLifecycle} 抽取到网关目录后才可执行，因此本方法接收该目录而不是自己去解析资源。
     *
     * @param gatewayDirectory 网关资源所在目录（已落盘的绝对路径），不可为 {@code null}
     * @return 命令与参数列表，保证非 {@code null} 且非空
     */
    List<String> startCommand(Path gatewayDirectory);

    /**
     * 获取网关进程的环境变量。
     * <p>
     * <b>是白名单而不是空环境</b>：清空环境会让解释器自己都找不到（{@code PATH} 缺失），
     * 因此实现方只透传必要变量（解释器搜索路径、语言运行时路径、区域设置…），
     * 并补上语言必需的强制项（例如 Python 的 {@code PYTHONUNBUFFERED}，否则 stdout 缓冲会与协议互相等死）。
     * <p>
     * 调用方（进程启动器）<b>必须用本方法的返回值完整替换</b>子进程环境，而不是叠加。
     *
     * @return 环境变量映射，保证非 {@code null}
     */
    Map<String, String> environment();
}
