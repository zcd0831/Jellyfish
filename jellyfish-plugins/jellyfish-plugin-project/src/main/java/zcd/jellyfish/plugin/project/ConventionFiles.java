package zcd.jellyfish.plugin.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * 项目约定文件的探测与读取：只看工作目录下有没有那个约定俗成的文件名。
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
 * <b>直接后果是「必须从仓库根目录启动」</b>：{@code AGENTS.md} 的行业位置是仓库根，而这个基准就是
 * 进程工作目录，两者只有在你从仓库根启动时才重合。这条前提写在 {@code README.md} 里。
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
     * 探测约定文件是否存在，存在时给出名称与大小。
     * <p>
     * <b>空文件视为不存在</b>：指引的唯一用途是让模型去读它，指向一个没有内容的文件只会白白消耗
     * 一次工具调用；而<b>不可读不视为不存在</b>——让模型的读取工具报出真实原因（权限、编码……），
     * 比插件在这里把它静默吞掉有用。
     *
     * @return 探测结果；未命中时返回 {@code null}
     */
    ConventionFile probe() {
        Path file = baseDirectory.resolve(CONVENTION_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            long size = Files.size(file);
            if (size > 0) {
                return new ConventionFile(CONVENTION_FILE, size);
            }
            LOG.debug("约定文件为空，跳过注入: {}", file);
        } catch (IOException e) {
            // 取不到大小（权限等）时按「存在但未知大小」处理：读不读得成让模型的读取工具去说，
            // 大小未知则一律走路径指引（内联的前提是「确定装得下」）
            LOG.debug("约定文件大小未知，按路径指引处理: {} reason={}", file, e.getMessage());
            return new ConventionFile(CONVENTION_FILE, -1L);
        }
        return null;
    }

    /**
     * 读取约定文件正文，最多读 {@code limit} 个字节。
     * <p>
     * <b>为什么要传上限而不是 {@code Files.readAllBytes}</b>：{@code probe} 与读取之间存在窗口，
     * 文件可能在两次系统调用之间被撑大；无上限读会把那个「刚好在窗口里写进来 1 GB 的文件」整份捞进内存。
     * 带上限读则把最坏情况钉死在配置的内联上限上。
     * <p>
     * <b>为什么用文件大小而不是上限来分配缓冲</b>：正常情况下大小才是真实需要的内存量，
     * 上限只是护栏。文件在窗口里变长时，多出来的部分被丢掉并把 {@link Reading#truncated()} 置真，
     * 由上层如实告诉模型「这份原文是截断的」——静默截断是别人踩过的坑，不值得再踩一次。
     *
     * @param file  探测结果，不可为 {@code null}
     * @param limit 读取上限（字节），必须为正
     * @return 读取结果；读取失败时返回 {@code null}，由上层退回路径指引
     */
    Reading read(ConventionFile file, int limit) {
        Path path = baseDirectory.resolve(file.name());
        byte[] buffer = new byte[(int) Math.min(Math.max(file.sizeInBytes(), 0L), (long) limit)];
        int total = 0;
        try (InputStream in = Files.newInputStream(path)) {
            while (total < buffer.length) {
                int read = in.read(buffer, total, buffer.length - total);
                if (read < 0) {
                    break;
                }
                total += read;
            }
            boolean truncated = in.read() >= 0;
            return new Reading(new String(buffer, 0, total, StandardCharsets.UTF_8), truncated);
        } catch (IOException e) {
            // 读不到就退回路径指引：模型的 read_file 会报出真实原因（权限、编码……），
            // 比插件在这里把它静默吞掉有用
            LOG.warn("约定文件读取失败，退回路径指引: {} reason={}", path, e.getMessage());
            return null;
        }
    }

    /**
     * 一次读取的结果：正文 + 是否被截断。
     * <p>
     * 不可变，可安全跨线程传递。
     *
     * @author zcd
     */
    static final class Reading {

        /** 文件正文（UTF-8 解码，非法字节按替换字符处理）。 */
        private final String text;

        /** 是否因为文件在探测之后变长而只读到一部分。 */
        private final boolean truncated;

        /**
         * 构造读取结果。
         *
         * @param text      文件正文
         * @param truncated 是否被截断
         */
        Reading(String text, boolean truncated) {
            this.text = text;
            this.truncated = truncated;
        }

        /**
         * 获取文件正文。
         *
         * @return 正文文本
         */
        String text() {
            return text;
        }

        /**
         * 判断是否被截断。
         *
         * @return 只读到一部分时返回 {@code true}
         */
        boolean truncated() {
            return truncated;
        }
    }
}
