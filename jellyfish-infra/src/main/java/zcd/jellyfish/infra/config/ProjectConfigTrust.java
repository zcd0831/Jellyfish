package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.infra.support.HomePaths;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 项目级配置的信任裁决：没被显式信任过的项目级配置文件一律不加载。
 * <p>
 * <b>为什么需要这道闸</b>：项目级配置（{@code ./.jellyfish/models.json}、{@code agents.json}、
 * {@code jellyfish.json}）的读取基准是进程的当前目录，因此「在某个目录里启动一次 jellyfish」
 * 就等于让那个目录里的文件参与决定运行参数。而经它可决定的东西很重：
 * <ul>
 *     <li>{@code models.json} —— 项目级同名 provider 是<b>整对象替换</b>，其中的
 *     {@code baseUrl} / {@code apiKey} / {@code vendorHeaders} 一旦被改写，
 *     该 provider 的密钥与全部对话正文都会送到别处；</li>
 *     <li>{@code agents.json} —— 项目级可以新增 agent 定义，而「未声明权限的 agent」是不受限的，
 *     等于凭空多出一个不受限制的身份；</li>
 *     <li>{@code jellyfish.json} —— {@code react.toolOutput.dir}、插件配置段等可以改落盘目录、
 *     拉起外部进程、让插件从别处加载脚本。</li>
 * </ul>
 * 这些内容会因为 {@code git clone} / {@code git pull} 凭空出现在工作目录里，因此默认<b>不加载</b>；
 * 只有被显式信任过的文件才参与合并。
 * <p>
 * <b>信任的单位是「文件绝对路径 + 内容指纹」，不是目录</b>。只记目录的话，一次 {@code git pull}
 * 带来的新配置会静默沿用旧的信任——那等于把「信任一次」变成「永远信任这个仓库，无论它以后写什么」。
 * 指纹对不上就重新征求确认，这是刻意的取舍：宁可多问一次，也不要一个会自己变宽的口子。
 * <p>
 * <b>三种授予方式</b>：
 * <ul>
 *     <li>{@link #trustEverythingInThisRun()} —— 本次进程内信任全部项目级配置，对应启动参数
 *     {@code --trust-project-config}；不落盘，因为它回答的是「我这次就是要用」；</li>
 *     <li>{@link #trustForSession(String)} —— 只让某一个文件在本次进程内生效，供交互里的
 *     「仅本次加载」；</li>
 *     <li>{@link #grant(String)} —— 按当前内容记进信任仓库（默认
 *     {@code ~/.jellyfish/trusted-project-configs.json}），后续进程直接生效。</li>
 * </ul>
 * <p>
 * <b>失败一律按「不信任」处理</b>：信任仓库读不出来、内容非法、文件读不到指纹，都只会让配置
 * 不被加载并记一条告警——闸门只会关得更紧，不会因为异常而自己打开。
 * <p>
 * 状态只有几个并发容器与一个 {@code volatile} 布尔，可安全跨线程使用。
 *
 * @author zcd
 */
@Singleton
public final class ProjectConfigTrust {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(ProjectConfigTrust.class);

    /** 信任仓库的缺省位置。 */
    static final String DEFAULT_STORE_PATH = "~/.jellyfish/trusted-project-configs.json";

    /** classpath 资源前缀，与 {@code SettingsReader} 同一约定。 */
    private static final String CLASSPATH_PREFIX = "classpath:";

    /** 信任仓库文件。 */
    private final Path storeFile;

    /** 已持久化的信任记录：配置文件绝对路径 → 内容指纹。 */
    private final Map<String, String> persisted = new ConcurrentHashMap<String, String>();

    /** 本次进程内单独授予的信任（「仅本次加载」）。 */
    private final Set<String> sessionGranted = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());

    /** 本次进程内是否信任全部项目级配置（{@code --trust-project-config}）。 */
    private volatile boolean trustEverything;

    /**
     * 按缺省位置构造（供依赖注入使用）。
     */
    @Inject
    public ProjectConfigTrust() {
        this(defaultStoreFile());
    }

    /**
     * 按指定信任仓库构造。
     * <p>
     * 供测试指向临时目录，也便于将来把仓库位置做成配置项。
     *
     * @param storeFile 信任仓库路径，不可为 {@code null}
     */
    public ProjectConfigTrust(Path storeFile) {
        this.storeFile = Objects.requireNonNull(storeFile, "storeFile must not be null");
        this.persisted.putAll(readStore(storeFile));
    }

    /**
     * 对一个项目级配置文件给出加载裁决。
     *
     * @param declaredPath 配置里声明的项目级路径（可含 {@code ~}，可相对），可为 {@code null}
     * @return 裁决结果，保证非 {@code null}
     */
    public Decision decide(String declaredPath) {
        if (isClasspathResource(declaredPath)) {
            // classpath 资源属于运行构件本身（由打包与部署的人决定），不是「当前目录里可能被塞进来的东西」，
            // 因此与全局级同性质，不在本闸的管辖范围内。这条不能少：Spring 接入方正是用它把配置放进
            // 自己的 src/main/resources，从而彻底不必依赖工作目录
            return Decision.LOAD;
        }
        Path file = absolute(declaredPath);
        if (file == null || !Files.isRegularFile(file)) {
            // 文件不存在：读出来也是 null，不必打扰用户，也不必告警
            return Decision.ABSENT;
        }
        if (trustEverything || sessionGranted.contains(file.toString())) {
            return Decision.LOAD;
        }
        String recorded = persisted.get(file.toString());
        if (recorded != null && recorded.equals(fingerprint(file))) {
            return Decision.LOAD;
        }
        return Decision.UNTRUSTED;
    }

    /**
     * 只让这一个文件在本次进程内生效，不落盘。
     *
     * @param declaredPath 项目级路径，可为 {@code null}
     */
    public void trustForSession(String declaredPath) {
        Path file = absolute(declaredPath);
        if (file != null) {
            sessionGranted.add(file.toString());
        }
    }

    /**
     * 本次进程内信任全部项目级配置，不落盘。
     */
    public void trustEverythingInThisRun() {
        trustEverything = true;
    }

    /**
     * 判断本次进程是否处于「信任全部」状态。
     *
     * @return 是返回 {@code true}
     */
    public boolean isTrustingEverythingInThisRun() {
        return trustEverything;
    }

    /**
     * 把某个项目级配置文件的当前内容记进信任仓库。
     * <p>
     * 记录的是<b>此刻的内容指纹</b>，因此之后这个文件一变就不再受信任。写盘失败也返回
     * {@code false} 而不是抛异常：用户刚刚做的确认不该因为一个写不进去的仓库而变成一次失败退出，
     * 但本进程内仍然按已信任处理（{@link #trustForSession(String)} 由调用方按需补上）。
     *
     * @param declaredPath 项目级路径，可为 {@code null}
     * @return 成功写入信任仓库返回 {@code true}
     */
    public boolean grant(String declaredPath) {
        Path file = absolute(declaredPath);
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        String hash = fingerprint(file);
        if (hash == null) {
            return false;
        }
        persisted.put(file.toString(), hash);
        return writeStore();
    }

    /**
     * 取信任仓库位置，供启动期提示与排查用。
     *
     * @return 仓库路径，保证非 {@code null}
     */
    public Path storeFile() {
        return storeFile;
    }

    /**
     * 判断声明路径是否指向 classpath 上的资源。
     *
     * @param declaredPath 声明路径，可为 {@code null}
     * @return 是 classpath 资源返回 {@code true}
     */
    private static boolean isClasspathResource(String declaredPath) {
        return declaredPath != null && declaredPath.trim().startsWith(CLASSPATH_PREFIX);
    }

    /**
     * 解析出配置文件的绝对规范路径。
     *
     * @param declaredPath 声明路径，可为 {@code null}
     * @return 绝对路径；入参空白时返回 {@code null}
     */
    private static Path absolute(String declaredPath) {
        if (StringUtils.isBlank(declaredPath)) {
            return null;
        }
        return Paths.get(HomePaths.expand(declaredPath.trim())).toAbsolutePath().normalize();
    }

    /**
     * 计算文件内容的指纹。
     *
     * @param file 目标文件，不可为 {@code null}
     * @return 指纹；读不出来时返回 {@code null}（调用方按未信任处理）
     */
    private static String fingerprint(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(file));
            StringBuilder text = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                text.append(Character.forDigit((value >> 4) & 0xF, 16));
                text.append(Character.forDigit(value & 0xF, 16));
            }
            return text.toString();
        } catch (IOException e) {
            LOG.warn("读取项目级配置失败，按未信任处理: {}（{}）", file, e.toString());
            return null;
        } catch (NoSuchAlgorithmException e) {
            throw new JellyfishException("SHA-256 不可用，无法校验项目级配置的信任", e);
        }
    }

    /**
     * 读取信任仓库。
     *
     * @param storeFile 仓库路径，不可为 {@code null}
     * @return 记录表；读不出来或内容非法时返回空表（等价于「一份都不信任」）
     */
    private static Map<String, String> readStore(Path storeFile) {
        Map<String, String> result = new ConcurrentHashMap<String, String>();
        if (!Files.isRegularFile(storeFile)) {
            return result;
        }
        try {
            String text = new String(Files.readAllBytes(storeFile), StandardCharsets.UTF_8);
            if (StringUtils.isBlank(text)) {
                return result;
            }
            Map<String, String> stored = ObjectMapperWrapper.readValue(text,
                    new TypeReference<Map<String, String>>() {
                    });
            if (stored != null) {
                for (Map.Entry<String, String> entry : stored.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        result.put(entry.getKey(), entry.getValue());
                    }
                }
            }
        } catch (IOException e) {
            LOG.warn("信任仓库读取失败，本进程按「一份项目级配置都不信任」处理: {}（{}）",
                    storeFile, e.toString());
        } catch (JellyfishException e) {
            LOG.warn("信任仓库内容非法，本进程按「一份项目级配置都不信任」处理: {}（{}）",
                    storeFile, e.getMessage());
        }
        return result;
    }

    /**
     * 原子写回信任仓库。
     *
     * @return 写入成功返回 {@code true}
     */
    private boolean writeStore() {
        try {
            Path parent = storeFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
            Files.write(temp, ObjectMapperWrapper.writeValueAsBytes(
                    new LinkedHashMap<String, String>(persisted)));
            Files.move(temp, storeFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            LOG.warn("信任记录写入失败，本次信任只在当前进程内有效: {}（{}）", storeFile, e.toString());
            return false;
        }
    }

    /**
     * 信任仓库的缺省位置。
     *
     * @return 路径，保证非 {@code null}
     */
    private static Path defaultStoreFile() {
        return Paths.get(HomePaths.expand(DEFAULT_STORE_PATH)).toAbsolutePath().normalize();
    }

    /**
     * 项目级配置文件的加载裁决。
     */
    public enum Decision {

        /** 加载：文件存在且已被信任。 */
        LOAD,

        /** 不加载：文件不存在（正常情形，不必告警）。 */
        ABSENT,

        /** 不加载：文件存在但没被信任（需要提示用户）。 */
        UNTRUSTED
    }
}
