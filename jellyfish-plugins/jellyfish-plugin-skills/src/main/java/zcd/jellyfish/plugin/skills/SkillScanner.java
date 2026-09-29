package zcd.jellyfish.plugin.skills;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * skill 目录扫描器：把「根目录列表」变成一份 {@link SkillScanResult}。
 * <p>
 * <b>唯一的约定是「子目录 / SKILL.md」</b>：名字固定，因此不存在「这个目录算不算一个 skill」的
 * 判别规则，也不需要第二份清单文件来声明有哪些 skill——目录里有什么就是什么。
 * <p>
 * <b>逐条目隔离</b>：单个目录读不出来、头部写坏了、缺 description，都只记一条问题并跳过它，
 * 不影响其余 skill。这与脚本桥接插件「一个脚本的清单问题不拖垮整门语言」是同一条取舍。
 * <p>
 * <b>同名时先到者胜</b>：根目录是有序的，因此「项目级覆盖用户级」只需把项目级写在前面，
 * 不需要一套覆盖声明。被压掉的那个记一条问题，而不是静默丢弃——否则用户会对着两个
 * 同名目录猜为什么改另一个没反应。
 * <p>
 * 无状态（不持有目录句柄），可安全复用。
 *
 * @author zcd
 */
final class SkillScanner {

    /** skill 定义文件名，固定。 */
    static final String SKILL_FILE = "SKILL.md";

    /** 单个 skill 最多列出的附带文件数。 */
    private static final int MAX_RESOURCES = 200;

    /** 附带文件的递归深度上限，避免把一个大仓库整个走一遍。 */
    private static final int MAX_RESOURCE_DEPTH = 5;

    /** skill 名称的展示长度上限。 */
    private static final int MAX_NAME_CHARS = 64;

    /**
     * 扫描全部根目录。
     *
     * @param roots  根目录列表，按优先级顺序，不可为 {@code null}
     * @param config 配置，不可为 {@code null}
     * @return 扫描结果，保证非 {@code null}
     */
    SkillScanResult scan(List<Path> roots, SkillsConfig config) {
        List<SkillDefinition> skills = new ArrayList<SkillDefinition>();
        List<String> issues = new ArrayList<String>();
        Set<String> names = new LinkedHashSet<String>();
        for (Path root : roots) {
            if (skills.size() >= config.maxSkills()) {
                issues.add("已达 maxSkills=" + config.maxSkills() + "，其余根目录未扫描");
                break;
            }
            scanRoot(root, config, skills, issues, names);
        }
        return new SkillScanResult(skills, issues);
    }

    /**
     * 扫描单个根目录。
     * <p>
     * <b>目录不存在是正常状态而不是问题</b>：冷启动、只有用户级没有项目级，都会走到这里。
     * 只有「存在但不是目录」与「不可读」才记问题——那两种才是用户需要知道的事。
     *
     * @param root   根目录
     * @param config 配置
     * @param skills 结果累积
     * @param issues 问题累积
     * @param names  已占用的名称
     */
    private void scanRoot(Path root, SkillsConfig config, List<SkillDefinition> skills,
                          List<String> issues, Set<String> names) {
        if (!Files.exists(root)) {
            return;
        }
        if (!Files.isDirectory(root)) {
            issues.add(root + "：不是目录，已跳过");
            return;
        }
        List<Path> entries = listEntries(root, issues);
        for (Path entry : entries) {
            if (skills.size() >= config.maxSkills()) {
                issues.add("已达 maxSkills=" + config.maxSkills() + "，其余 skill 未加载");
                return;
            }
            if (!Files.isDirectory(entry)) {
                continue;
            }
            Path bodyPath = entry.resolve(SKILL_FILE);
            if (!Files.isRegularFile(bodyPath)) {
                continue;
            }
            SkillDefinition definition = build(entry, bodyPath, issues);
            if (definition == null) {
                continue;
            }
            if (!names.add(definition.name())) {
                issues.add(entry + "：名称 " + definition.name() + " 已被更靠前的根目录占用，已跳过");
                continue;
            }
            skills.add(definition);
        }
    }

    /**
     * 列出根目录下的条目并按名称排序。
     * <p>
     * 排序是刻意的：加载顺序决定同名时谁胜出，而目录列举顺序在文件系统之间并不稳定。
     * 不排序的话，「谁覆盖谁」会随机器而变。
     *
     * @param root   根目录
     * @param issues 问题累积
     * @return 有序条目列表，保证非 {@code null}
     */
    private static List<Path> listEntries(Path root, List<String> issues) {
        List<Path> entries = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path entry : stream) {
                entries.add(entry);
            }
        } catch (IOException e) {
            issues.add(root + "：目录不可读，已跳过（" + e.getMessage() + "）");
            return Collections.emptyList();
        }
        Collections.sort(entries);
        return entries;
    }

    /**
     * 读取并校验单个 skill 目录。
     *
     * @param directory skill 目录
     * @param bodyPath  {@code SKILL.md} 路径
     * @param issues    问题累积
     * @return 定义；条目不合法时返回 {@code null} 并记一条问题
     */
    private SkillDefinition build(Path directory, Path bodyPath, List<String> issues) {
        String content;
        try {
            content = new String(Files.readAllBytes(bodyPath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            issues.add(bodyPath + "：读取失败，已跳过（" + e.getMessage() + "）");
            return null;
        }
        SkillFrontmatter frontmatter = SkillFrontmatter.parse(content);
        String name = normalizeName(frontmatter.name());
        if (name == null) {
            name = normalizeName(directory.getFileName() == null ? null : directory.getFileName().toString());
        }
        if (name == null) {
            issues.add(bodyPath + "：无法确定 skill 名称（头部无 name 且目录名不可用），已跳过");
            return null;
        }
        String description = frontmatter.description();
        if (description == null) {
            // 描述是模型选择 skill 的唯一依据，没有它这个 skill 永远不会被加载，
            // 与其让它在清单里占一行废信息，不如明确跳过并说出来
            issues.add(bodyPath + "：缺少 description，模型无从判断何时使用，已跳过");
            return null;
        }
        Resources resources = listResources(directory, bodyPath);
        int bodyBytes = frontmatter.body().getBytes(StandardCharsets.UTF_8).length;
        return new SkillDefinition(name, description, directory, bodyPath, bodyBytes,
                resources.paths, resources.truncated);
    }

    /**
     * 归一化名称：折叠空白、去首尾、截断到展示上限。
     * <p>
     * 名称会被拼进每轮的 system prompt 清单，因此换行与制表符必须先折叠掉——
     * 一个带换行的目录名能把清单的排版整个打乱。
     *
     * @param raw 原始名称，可为 {@code null}
     * @return 归一化后的名称；不可用时返回 {@code null}
     */
    private static String normalizeName(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.replaceAll("\\s+", " ").trim();
        if (name.isEmpty()) {
            return null;
        }
        return name.length() <= MAX_NAME_CHARS ? name : name.substring(0, MAX_NAME_CHARS);
    }

    /**
     * 列出 skill 目录下的附带文件。
     * <p>
     * 不跟随符号链接（{@code Files.walk} 的默认行为）：跟随会让一个指向上级目录的软链接
     * 把整个仓库走一遍，而这不是用户想要的。
     *
     * @param directory skill 目录
     * @param bodyPath  {@code SKILL.md} 路径（从结果里排除）
     * @return 附带文件清单，保证非 {@code null}
     */
    private static Resources listResources(Path directory, Path bodyPath) {
        List<String> paths = new ArrayList<String>();
        boolean truncated = false;
        try (Stream<Path> stream = Files.walk(directory, MAX_RESOURCE_DEPTH)) {
            List<Path> files = new ArrayList<Path>();
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (Files.isRegularFile(path) && !path.equals(bodyPath)) {
                    files.add(path);
                }
            }
            Collections.sort(files);
            for (Path path : files) {
                if (paths.size() >= MAX_RESOURCES) {
                    truncated = true;
                    break;
                }
                paths.add(directory.relativize(path).toString().replace('\\', '/'));
            }
        } catch (IOException e) {
            // 附带文件列不出来不影响「正文可读」这件主要目的，因此不当成扫描问题上报
            return new Resources(Collections.<String>emptyList(), false);
        }
        return new Resources(paths, truncated);
    }

    /**
     * 附带文件清单的内部载体：列表 + 是否被截断。
     * <p>
     * 用一个两字段的小对象而不是 {@code List} 的子类，是因为「截断」这件事属于清单本身的属性，
     * 而消费方（工具输出、台账）都需要说出来——把 {@code +N} 藏进列表元素里，两边都得再解一次。
     */
    private static final class Resources {

        /** 相对路径列表。 */
        private final List<String> paths;

        /** 是否因超过上限而截断。 */
        private final boolean truncated;

        /**
         * 构造清单。
         *
         * @param paths     相对路径列表
         * @param truncated 是否截断
         */
        private Resources(List<String> paths, boolean truncated) {
            this.paths = paths;
            this.truncated = truncated;
        }
    }
}
