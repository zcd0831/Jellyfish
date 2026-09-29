package zcd.jellyfish.plugin.skills;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code /skills} 命令的台账渲染：把配置、当前清单与扫描期问题落成一段可读文本。
 * <p>
 * <b>为什么这条命令必须有</b>：skill「没生效」的现场表现全部是「模型看不见它」——目录写错了、
 * 描述忘了写、名称被前面的根目录占了。这些都是扫描期的事实，而扫描期没有任何界面。
 * 把台账放在一条命令上，排查就只需要敲一次，不必去翻日志。
 * <p>
 * <b>问题与结果一起打印</b>：只说「加载了 3 个」而不说「有 2 个因为缺 description 被跳过」，
 * 用户会以为自己的目录里就那 3 个。
 * <p>
 * 不能实例化。
 *
 * @author zcd
 */
final class SkillsLedger {

    /**
     * 工具类，禁止实例化。
     */
    private SkillsLedger() {
    }

    /**
     * 渲染台账。
     *
     * @param config  配置，不可为 {@code null}
     * @param catalog skill 目录缓存，不可为 {@code null}
     * @return 台账文本，保证非 {@code null}
     */
    static String render(SkillsConfig config, SkillCatalog catalog) {
        SkillScanResult snapshot = catalog.current();
        StringBuilder text = new StringBuilder("skills 插件\n");
        text.append("启用: ").append(config.enabled()).append('\n');
        text.append("根目录:\n");
        for (Path root : config.roots()) {
            text.append("  - ").append(root).append("（").append(rootState(root, snapshot)).append("）\n");
        }
        text.append("已加载 ").append(snapshot.skills().size()).append(" 个");
        if (snapshot.isEmpty()) {
            text.append("：没有任何可用 skill（缺 description 或目录里没有 SKILL.md）\n");
        } else {
            text.append(":\n");
            for (SkillDefinition skill : snapshot.skills()) {
                text.append("  - ").append(skill.name()).append('：')
                        .append(SkillText.singleLine(skill.description(), 80))
                        .append("（").append(SkillText.humanBytes(skill.bodyBytes())).append("，")
                        .append(skill.resources().size()).append(" 个附带文件）\n");
            }
        }
        if (!snapshot.issues().isEmpty()) {
            text.append("问题:\n");
            for (String issue : snapshot.issues()) {
                text.append("  - ").append(issue).append('\n');
            }
        }
        return text.toString();
    }

    /**
     * 描述一个根目录的当前状态：不存在、不是目录、还是加载了几个。
     * <p>
     * <b>「不存在」要明确写出来</b>：冷启动时项目级根目录通常还不存在，而用户此刻正要判断
     * 「我该把 skill 放哪儿」。空着不写会让人以为配置没读进去。
     *
     * @param root     根目录
     * @param snapshot 当前扫描结果
     * @return 状态文本
     */
    private static String rootState(Path root, SkillScanResult snapshot) {
        if (!Files.exists(root)) {
            return "不存在";
        }
        if (!Files.isDirectory(root)) {
            return "不是目录";
        }
        int count = 0;
        for (SkillDefinition skill : snapshot.skills()) {
            if (skill.directory().startsWith(root)) {
                count++;
            }
        }
        return count + " 个";
    }
}
