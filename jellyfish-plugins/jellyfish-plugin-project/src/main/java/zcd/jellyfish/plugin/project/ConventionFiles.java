package zcd.jellyfish.plugin.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * 项目约定文件的探测：只看工作目录下有没有那个约定俗成的文件名。
 * <p>
 * <b>为什么只认一个固定文件名</b>：{@code AGENTS.md} 已是行业统一的约定（各家编码 agent 都读它），
 * 把它做成配置项只会多一个「填错了也不知道」的旋钮，而不会多出一种真实需求。
 * 哪天确实需要第二个文件名，再加也不迟——那时它已是事实而非猜测。
 * <p>
 * <b>为什么基准是进程工作目录</b>：与 {@code ToolPaths} 同一口径。工具的相对路径按进程工作目录解析，
 * 若约定文件按另一个基准找，就会出现「工具按 A 解析、约定按 B 解析」的错位。会话级 cwd 落地时，
 * 两处一起改。
 * <p>
 * <b>为什么不向上查找父目录、不查用户主目录</b>：那是「往上找几层、找到根还是找到 home」的独立策略，
 * 与本插件要解决的问题（让模型知道当前目录有约定文件）不是一回事。少做一个策略，就少一份要维护的语义。
 * <p>
 * 无状态且线程安全：除构造时的基准目录外不持有任何可变状态。
 *
 * @author zcd
 */
final class ConventionFiles {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ConventionFiles.class);

    /** 约定文件名：探测的目标，也是给模型的指引里出现的那个相对路径。 */
    static final String CONVENTION_FILE = "AGENTS.md";

    /** 约定文件的查找基准目录。 */
    private final Path baseDirectory;

    /**
     * 构造探测器。
     *
     * @param baseDirectory 查找基准目录，不可为 {@code null}
     */
    ConventionFiles(Path baseDirectory) {
        this.baseDirectory = Objects.requireNonNull(baseDirectory, "baseDirectory must not be null");
    }

    /**
     * 按进程工作目录构造探测器。
     * <p>
     * 在调用时现取工作目录而不是放在 {@code static final} 字段里：插件启动只调用它一次，
     * 现取能避开「类加载时机决定基准目录」这种隐式时序。
     *
     * @return 以进程工作目录为基准的探测器
     */
    static ConventionFiles ofWorkingDirectory() {
        return new ConventionFiles(Paths.get("").toAbsolutePath().normalize());
    }

    /**
     * 获取查找基准目录。
     *
     * @return 基准目录的绝对路径
     */
    Path baseDirectory() {
        return baseDirectory;
    }

    /**
     * 探测约定文件是否存在，存在时返回给模型的相对路径。
     * <p>
     * <b>空文件视为不存在</b>：指引的唯一用途是让模型去读它，指向一个没有内容的文件只会白白消耗
     * 一次工具调用；而<b>不可读不视为不存在</b>——让模型的读取工具报出真实原因（权限、编码……），
     * 比插件在这里把它静默吞掉有用。
     *
     * @return 存在的约定文件的相对路径；未命中时返回 {@code null}
     */
    String presentName() {
        Path file = baseDirectory.resolve(CONVENTION_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            if (Files.size(file) > 0) {
                return CONVENTION_FILE;
            }
            LOG.debug("约定文件为空，跳过注入: {}", file);
        } catch (IOException e) {
            // 取不到大小（权限等）时按「存在」处理：读不读得成让模型的读取工具去说
            LOG.debug("约定文件大小未知，仍按存在处理: {} reason={}", file, e.getMessage());
            return CONVENTION_FILE;
        }
        return null;
    }
}
