package zcd.jellyfish.script;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.script.codec.ExtensionCodecs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 脚本目录扫描器：把 {@code scriptsRoot} 下的每个子目录读成一份清单。
 * <p>
 * <b>发现规则</b>（每条都对应一种真实会发生的写法错误）：
 * <ul>
 *     <li>根目录<b>不存在</b> = 空结果（正常的冷启动状态：用户还没建脚本目录，或从未用过脚本）；
 *     根目录存在但不是目录、或不可读 = 抛异常（全局故障，没有脚本能加载）；</li>
 *     <li>只扫<b>子目录</b>，每个子目录要求有 {@link ScriptManifest#FILE_NAME}；缺失即问题；</li>
 *     <li>{@code id} 缺省用目录名；两个目录声明同一个 {@code id} 时后者被跳过——
 *     否则两者的注册会在同一个 owner 下互相覆盖，而且覆盖顺序取决于目录遍历顺序；</li>
 *     <li>{@code entry} 指向的文件必须存在：否则问题会被推迟到「第一次调用该脚本」才暴露，
 *     而那已经是用户在使用工具的时候了。</li>
 * </ul>
 * 子目录按名字排序遍历，保证「谁被跳过」在任何机器上都一致。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ScriptPluginScanner {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ScriptPluginScanner.class);

    /** 扩展点编解码器注册表，用于校验清单里的扩展点取值。 */
    private final ExtensionCodecs codecs;

    /**
     * 构造扫描器。
     *
     * @param codecs 扩展点编解码器注册表，不可为 {@code null}
     */
    public ScriptPluginScanner(ExtensionCodecs codecs) {
        this.codecs = codecs;
    }

    /**
     * 扫描脚本目录。
     *
     * @param scriptsRoot 脚本根目录，不可为 {@code null}
     * @return 扫描结果，保证非 {@code null}
     * @throws JellyfishException 根目录存在但不是目录、或无法读取时抛出
     */
    public ScriptScanResult scan(Path scriptsRoot) {
        if (!Files.exists(scriptsRoot)) {
            LOG.debug("脚本根目录不存在，按空清单处理: {}", scriptsRoot);
            return new ScriptScanResult(new ArrayList<ScriptPlugin>(), new ArrayList<ScriptIssue>());
        }
        if (!Files.isDirectory(scriptsRoot)) {
            throw new JellyfishException("脚本根目录不是目录: " + scriptsRoot);
        }
        List<Path> directories = listDirectories(scriptsRoot);
        List<ScriptPlugin> plugins = new ArrayList<ScriptPlugin>();
        List<ScriptIssue> issues = new ArrayList<ScriptIssue>();
        Set<String> seenIds = new LinkedHashSet<String>();
        for (Path directory : directories) {
            loadScript(directory, seenIds, plugins, issues);
        }
        LOG.info("脚本目录扫描完成: root={} plugins={} issues={}", scriptsRoot, plugins.size(), issues.size());
        return new ScriptScanResult(plugins, issues);
    }

    /**
     * 加载单个脚本目录，把结果记入输出集合。
     *
     * @param directory 脚本目录
     * @param seenIds   已出现的脚本标识
     * @param plugins   成功加载的脚本输出
     * @param issues    问题输出
     */
    private void loadScript(Path directory, Set<String> seenIds, List<ScriptPlugin> plugins,
                            List<ScriptIssue> issues) {
        String name = directory.getFileName().toString();
        Path manifestFile = directory.resolve(ScriptManifest.FILE_NAME);
        if (!Files.isRegularFile(manifestFile)) {
            issues.add(new ScriptIssue(name, "缺少 " + ScriptManifest.FILE_NAME));
            return;
        }
        ScriptManifest manifest;
        try {
            manifest = ScriptManifest.parse(read(manifestFile), name, codecs);
        } catch (RuntimeException e) {
            // 清单问题是「该脚本不可用」，不是「内核出故障」：逐条记录下来继续下一个目录
            issues.add(new ScriptIssue(name, messageOf(e)));
            return;
        }
        if (!seenIds.add(manifest.id())) {
            issues.add(new ScriptIssue(name, "脚本标识重复: " + manifest.id()));
            return;
        }
        ScriptPlugin plugin = new ScriptPlugin(manifest.id(), directory, manifest);
        if (!Files.isRegularFile(plugin.entryFile())) {
            issues.add(new ScriptIssue(name, "入口文件不存在: " + manifest.entry()));
            return;
        }
        plugins.add(plugin);
    }

    /**
     * 列出脚本根目录下的子目录，按名字升序。
     *
     * @param scriptsRoot 脚本根目录
     * @return 子目录列表，保证非 {@code null}
     * @throws JellyfishException 遍历失败时抛出
     */
    private static List<Path> listDirectories(Path scriptsRoot) {
        List<Path> directories = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(scriptsRoot)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    directories.add(entry);
                }
            }
        } catch (IOException e) {
            throw new JellyfishException("无法读取脚本根目录: " + scriptsRoot, e);
        }
        Collections.sort(directories, new Comparator<Path>() {
            @Override
            public int compare(Path left, Path right) {
                return left.getFileName().toString().compareTo(right.getFileName().toString());
            }
        });
        return directories;
    }

    /**
     * 读取清单文件。
     *
     * @param file 清单文件
     * @return 正文
     * @throws JellyfishException 读取失败时抛出
     */
    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JellyfishException("无法读取清单: " + file, e);
        }
    }

    /**
     * 取异常的可读消息，消息为空时退回类名。
     *
     * @param throwable 异常
     * @return 消息文本，保证非 {@code null}
     */
    private static String messageOf(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty() ? throwable.getClass().getName() : message;
    }
}
