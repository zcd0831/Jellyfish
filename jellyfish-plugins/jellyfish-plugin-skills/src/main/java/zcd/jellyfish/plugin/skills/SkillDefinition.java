package zcd.jellyfish.plugin.skills;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个被发现的 skill 的元信息：这些字段全部来自扫描期的一次读取，不随后续文件改动而更新
 * ——文件改了，扫描结果会被重扫替换（见 {@code SkillCatalog}），而不是就地修改。
 * <p>
 * <b>正文不在本对象里</b>：{@code SKILL.md} 的正文只在模型真正加载这个 skill 时才需要，
 * 常驻的只有 {@code name} 与 {@code description}。把正文一起缓存下来，等于给每个 skill 常驻一份
 * 谁也不读的文本，也让「改了正文要重扫」变成一个本不存在的问题。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class SkillDefinition {

    /** skill 名称，同时是 {@code skill} 工具的参数取值。 */
    private final String name;

    /** 一句话描述，模型据此判断何时加载；不为 {@code null}（没有描述的 skill 在扫描期就被跳过）。 */
    private final String description;

    /** skill 目录的绝对路径。 */
    private final Path directory;

    /** {@code SKILL.md} 的绝对路径。 */
    private final Path bodyPath;

    /** 正文的 UTF-8 字节数，用于台账展示与「本次加载会不会被截断」的预告。 */
    private final int bodyBytes;

    /** 附带文件的相对路径（以 {@code /} 分隔），已排序；不含 {@code SKILL.md} 自身。 */
    private final List<String> resources;

    /** 附带文件是否因超过扫描上限而被截断。 */
    private final boolean resourcesTruncated;

    /**
     * 构造定义。
     *
     * @param name                skill 名称
     * @param description         一句话描述
     * @param directory           skill 目录
     * @param bodyPath            {@code SKILL.md} 路径
     * @param bodyBytes           正文字节数
     * @param resources           附带文件相对路径
     * @param resourcesTruncated  附带文件是否被截断
     */
    SkillDefinition(String name, String description, Path directory, Path bodyPath, int bodyBytes,
                    List<String> resources, boolean resourcesTruncated) {
        this.name = name;
        this.description = description;
        this.directory = directory;
        this.bodyPath = bodyPath;
        this.bodyBytes = bodyBytes;
        this.resources = Collections.unmodifiableList(new ArrayList<String>(resources));
        this.resourcesTruncated = resourcesTruncated;
    }

    /**
     * 获取 skill 名称。
     *
     * @return 名称
     */
    String name() {
        return name;
    }

    /**
     * 获取一句话描述。
     *
     * @return 描述，保证非 {@code null}
     */
    String description() {
        return description;
    }

    /**
     * 获取 skill 目录。
     *
     * @return 目录绝对路径
     */
    Path directory() {
        return directory;
    }

    /**
     * 获取 {@code SKILL.md} 路径。
     *
     * @return 正文路径
     */
    Path bodyPath() {
        return bodyPath;
    }

    /**
     * 获取正文字节数。
     *
     * @return UTF-8 字节数
     */
    int bodyBytes() {
        return bodyBytes;
    }

    /**
     * 获取附带文件相对路径。
     *
     * @return 不可变有序列表，保证非 {@code null}
     */
    List<String> resources() {
        return resources;
    }

    /**
     * 判断附带文件是否因超过扫描上限而被截断。
     *
     * @return 被截断返回 {@code true}
     */
    boolean resourcesTruncated() {
        return resourcesTruncated;
    }

    @Override
    public String toString() {
        return "SkillDefinition{name=" + name + ", directory=" + directory + '}';
    }
}
