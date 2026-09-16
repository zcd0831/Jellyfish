package zcd.jellyfish.script;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 脚本进程的启动器：把「怎么起一个能被逐行对话的子进程」收在一个接口后面。
 * <p>
 * <b>它是 {@code ScriptGateway} 唯一的外部依赖接缝</b>。网关的核心逻辑是「懒启动、初始化、
 * 转发、空闲时退场」，这些判断与「子进程是不是真的被 {@code fork} 出来」无关；
 * 有了这个接口，网关的全部状态机都能用一个内存假进程单测，
 * 而真实进程只在一处（{@code CommonsExecScriptProcess}）与操作系统打交道。
 * <p>
 * 无法用假进程验证的部分只剩「管道是否真的能承载逐行 JSON」——那由集成测试用一个
 * 用 Java 写的回声子进程覆盖，不需要 Python。
 *
 * @author zcd
 */
@FunctionalInterface
public interface ScriptProcessFactory {

    /**
     * 启动一个脚本进程。
     * <p>
     * <b>两个回调都不会被实现方吞掉</b>：读到一行就必须交出去（否则等待应答的一方只会等到超时），
     * 进程退出就必须报一次（否则网关不知道自己已经失效）。它们由实现方的读取线程调用，
     * 因此调用方不应当在里面做长时间工作。
     *
     * @param lines  每读到一行协议文本回调一次，不可为 {@code null}
     * @param onExit 进程退出时回调一次，参数为退出码，不可为 {@code null}
     * @return 进程句柄，保证非 {@code null}
     * @throws zcd.jellyfish.api.JellyfishException 进程无法启动时抛出（如解释器不存在）
     */
    ScriptProcess start(Consumer<String> lines, Consumer<Integer> onExit);

    /**
     * 构造一个用操作系统命令启动进程的工厂（基于 Apache Commons Exec）。
     * <p>
     * 用静态工厂而不是实现类暴露在外的原因：实现类留在包内，
     * 调用方（桥接插件）拿到的只是一个接口，因此将来换进程实现不需要动插件。
     *
     * @param command     命令与参数，不可为 {@code null} 且非空
     * @param environment 完整替换子进程的环境变量，不可为 {@code null}；空映射即空环境
     * @param directory   子进程工作目录，可为 {@code null}（继承当前进程）
     * @return 进程工厂
     */
    static ScriptProcessFactory osProcess(List<String> command, Map<String, String> environment,
                                         Path directory) {
        List<String> frozenCommand = Collections.unmodifiableList(command);
        Map<String, String> frozenEnvironment =
                Collections.unmodifiableMap(new LinkedHashMap<String, String>(environment));
        return (lines, onExit) -> new CommonsExecScriptProcess(frozenCommand, frozenEnvironment,
                directory, lines, onExit);
    }
}
