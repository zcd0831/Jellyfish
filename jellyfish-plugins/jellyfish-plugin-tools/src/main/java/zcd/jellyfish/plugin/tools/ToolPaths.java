package zcd.jellyfish.plugin.tools;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 工具的文件路径约定：统一解析与展示口径。
 * <p>
 * 两个刻意的决定：
 * <ul>
 *     <li><b>相对路径按进程工作目录解析</b>：{@code ToolCallRequest} 不带工作目录，
 *     会话里也没有 cwd 这个概念，因此基准只能是进程启动目录。把它集中在这里，
 *     将来补上会话级 cwd 时只改这一处；</li>
 *     <li><b>展示用相对路径</b>：输出里的绝对路径又长又难读，还会让相邻两次调用的输出无法比对；
 *     落在工作目录内的路径统一转成相对形式，目录外才退回绝对路径。</li>
 * </ul>
 * <p>
 * 这里<b>不做</b>越界拦截：能否访问由权限层判定（agent 授权、PLAN 只读白名单），
 * 工具自己再收一道窄口子只会让「读工作目录外的文件」这种正常需求无法完成。
 *
 * @author zcd
 */
final class ToolPaths {

    /** 进程工作目录，相对路径的解析基准。 */
    private static final Path WORKING_DIRECTORY = Paths.get("").toAbsolutePath().normalize();

    /**
     * 工具类，禁止实例化。
     */
    private ToolPaths() {
    }

    /**
     * 把模型给的路径解析成规范化的绝对路径。
     *
     * @param raw 原始路径，不可为空白
     * @return 规范化绝对路径
     */
    static Path resolve(String raw) {
        return Paths.get(raw).toAbsolutePath().normalize();
    }

    /**
     * 把路径转成适合放进工具输出的文本。
     *
     * @param path 路径
     * @return 工作目录内为相对路径，目录外为绝对路径
     */
    static String display(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(WORKING_DIRECTORY)) {
            return absolute.toString();
        }
        String relative = WORKING_DIRECTORY.relativize(absolute).toString();
        return relative.isEmpty() ? "." : relative;
    }
}
