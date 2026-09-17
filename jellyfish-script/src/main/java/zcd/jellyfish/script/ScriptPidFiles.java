package zcd.jellyfish.script;

import zcd.jellyfish.api.JellyfishException;

import java.nio.file.Path;

/**
 * 网关 PID 文件的路径计算。
 * <p>
 * <b>它的唯一用途是事后排查</b>：正常路径上没有任何东西读它——进程回收靠父子关系、
 * 空闲自毁靠网关自己的循环、JVM 退出靠关闭钩子。只有在「JVM 被 {@code kill -9}、
 * 网关也一起失联」之后，人需要知道刚才那批进程是谁，这时 PID 文件是唯一还留着的线索。
 * 因此它<b>只报告、不处置</b>：本类不做任何存活判定，更不做任何 kill。
 * <p>
 * <b>为什么路径由 Java 侧算、再下发给网关</b>：与其它网关设置同理——每种语言的网关各写一份
 * 「主目录怎么取、目录怎么拼」的实现，三份里必然有两份会漂移。网关只接受一个已经算好的路径。
 * <p>
 * <b>文件名按语言而不是按内容摘要分</b>：网关资源目录名带内容摘要（见 {@link GatewayResources}），
 * 于是「资源改了一个字节」就会换目录；PID 文件若跟着摘要走，上一代遗留的那份会被新摘要目录
 * 挡在外面永远看不到，而它的全部价值就在「被下一次启动看见」。用户主目录下的
 * {@code jellyfish/pids/} 不会因内容而变，因此能一直充当这个交接点。
 *
 * @author zcd
 */
public final class ScriptPidFiles {

    /** 文件名前缀。 */
    private static final String PREFIX = "script-";

    /** 文件名后缀。 */
    private static final String SUFFIX = ".pid";

    /**
     * 常量类风格的私有构造器。
     */
    private ScriptPidFiles() {
    }

    /**
     * 计算默认目录（{@code ~/jellyfish/pids}）。
     * <p>
     * 从 {@link GatewayResources#defaultBaseDirectory()} 的父目录派生，而不是自己再拼一遍
     * {@code user.home}：两处各写一遍，改动一处就会出现「网关抽到 A、PID 写在 B」这种
     * 只有真去翻文件才会发现的偏差。
     *
     * @return 默认目录
     */
    public static Path defaultDirectory() {
        Path gatewayRoot = GatewayResources.defaultBaseDirectory();
        Path base = gatewayRoot.getParent();
        return base == null ? gatewayRoot.resolve("pids") : base.resolve("pids");
    }

    /**
     * 计算某个语言的 PID 文件路径。
     *
     * @param directory  PID 文件目录，不可为 {@code null}
     * @param languageId 语言标识，不可为空白
     * @return PID 文件路径
     * @throws JellyfishException 参数非法时抛出
     */
    public static Path pathFor(Path directory, String languageId) {
        if (directory == null) {
            throw new JellyfishException("PID 文件目录不可为 null");
        }
        return directory.resolve(fileName(languageId));
    }

    /**
     * 计算某个语言的 PID 文件名。
     * <p>
     * 拒绝路径分隔符：语言标识由语言适配自己声明，拼进文件名就成了一段路径——
     * 一个写着 {@code ../..} 的标识会把文件写到目录外面去，而这类标识在配置正确时永远不会出现。
     *
     * @param languageId 语言标识，不可为空白
     * @return 文件名
     * @throws JellyfishException 标识为空白或含路径分隔符时抛出
     */
    public static String fileName(String languageId) {
        if (languageId == null || languageId.trim().isEmpty()) {
            throw new JellyfishException("语言标识不可为空白，无法推导 PID 文件名");
        }
        String id = languageId.trim();
        if (id.indexOf('/') >= 0 || id.indexOf('\\') >= 0 || id.contains("..")) {
            throw new JellyfishException("语言标识不得包含路径分隔符: " + id);
        }
        return PREFIX + id + SUFFIX;
    }
}
