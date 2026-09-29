package zcd.jellyfish.plugin.skills;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * skill 目录的缓存：把「扫描一次的代价」与「每轮都要问一次的频率」隔开。
 * <p>
 * <b>为什么必须缓存</b>：清单贡献每轮组装请求时都会被问一次，而扫描要列举目录、读每个
 * {@code SKILL.md}——把这些放进每轮的路径，等于让对话的每一轮都付一次磁盘遍历的钱。
 * <p>
 * <b>为什么缓存又必须会自动失效</b>：{@code /reload} 只会重启「配置段变了」的插件，
 * 而编辑一个 {@code SKILL.md} 并不改配置。不做失效的话，「我改了 skill 却不生效」只能靠重启进程解决。
 * <p>
 * <b>失效判据是「文件系统签名的变化」，不是时间</b>：签名由各根目录与已发现 skill 的
 * 目录 / {@code SKILL.md} 的修改时间拼成，因此新增、删除、改名、编辑都会被看见，而
 * 没有任何改动时一次扫描都不会发生。判据只做 O(根目录数 + skill 数) 次 {@code stat}，
 * 不列举目录、不读文件——这正是它能被放进每轮路径的原因。
 * <p>
 * <b>为什么不用「多久重扫一次」这种时间判据</b>：它要么太频繁（白扫），要么太稀疏
 * （改了之后一段时间不生效），而「改了却不知道」恰恰是这里唯一要避免的事。
 * <p>
 * 线程安全。
 *
 * @author zcd
 */
class SkillCatalog {

    /** 配置：根目录与各项上限。 */
    private final SkillsConfig config;

    /** 扫描器；无状态，可复用。 */
    private final SkillScanner scanner = new SkillScanner();

    /** 最近一次扫描结果。 */
    private volatile SkillScanResult snapshot;

    /** 最近一次扫描之后计算的文件系统签名。 */
    private volatile String signature;

    /**
     * 构造目录缓存。
     *
     * @param config 配置，不可为 {@code null}
     */
    SkillCatalog(SkillsConfig config) {
        this.config = config;
    }

    /**
     * 取当前可用的 skill 清单，必要时先重扫。
     *
     * @return 扫描结果，保证非 {@code null}
     */
    synchronized SkillScanResult current() {
        String observed = filesystemSignature();
        if (snapshot == null || !observed.equals(signature)) {
            snapshot = scanner.scan(config.roots(), config);
            // 重扫之后再算一次签名而不是复用 observed：扫描期间文件可能又变了，
            // 复用会把那次改动记成「已看见」，于是它要等到下一次改动才生效
            signature = filesystemSignature();
        }
        return snapshot;
    }

    /**
     * 计算文件系统签名：各根目录与全部已发现 skill 的目录 / {@code SKILL.md} 的修改时间。
     * <p>
     * 首次调用（还没有扫描结果）时只有根目录，这没错：那时该看见的是「根目录层面有没有变化」。
     *
     * @return 签名文本
     */
    private String filesystemSignature() {
        StringBuilder signatureBuilder = new StringBuilder();
        for (Path root : config.roots()) {
            signatureBuilder.append(root).append('=').append(lastModified(root)).append(';');
        }
        SkillScanResult current = snapshot;
        if (current != null) {
            for (SkillDefinition skill : current.skills()) {
                signatureBuilder.append(skill.directory()).append('=')
                        .append(lastModified(skill.directory())).append(',')
                        .append(skill.bodyPath()).append('=').append(lastModified(skill.bodyPath()))
                        .append(';');
            }
        }
        return signatureBuilder.toString();
    }

    /**
     * 取路径的修改时间。
     *
     * @param path 路径，可为 {@code null}
     * @return 毫秒时间戳；路径不存在或不可读时返回 {@code -1}
     */
    private static long lastModified(Path path) {
        if (path == null) {
            return -1L;
        }
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return -1L;
        }
    }
}
