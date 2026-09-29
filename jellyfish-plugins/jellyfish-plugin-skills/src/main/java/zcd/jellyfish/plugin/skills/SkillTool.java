package zcd.jellyfish.plugin.skills;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.extension.ToolMetadata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具 {@code skill}：把一个 skill 的正文加载进本次对话。
 * <p>
 * <b>「渐进披露」的中间那一步</b>：常驻上下文里只有名称与描述（见 {@code SkillPromptContribution}），
 * 模型判断需要时再调本工具把正文取回来。因此本工具的返回值<b>就是</b>注入上下文的内容，
 * 它走的链路与其它工具结果完全一致——会被截断、会落盘、会被老化成 stub，不需要另开一条注入通道。
 * <p>
 * <b>名称不写进参数 Schema 的 enum</b>：{@code ToolDescriptor} 在注册那一刻就固定了，而 skill 是
 * 目录里现扫出来的、随时会变。把清单放进 enum 就意味着「新增一个 skill 要重新注册工具」，
 * 而放进提示词贡献则是每轮现算——这与子代理把「可委派类型」放提示词贡献而不是 enum 是同一条理由。
 * <p>
 * <b>只读</b>：本工具只读文件，没有任何副作用，因此在 PLAN 模式下同样可用——
 * 读一份说明本来就不该需要写权限。
 * <p>
 * 无状态，可安全复用。
 *
 * @author zcd
 */
final class SkillTool implements ExtensionHandler<ToolCallRequest, ToolCallResult> {

    /** 工具名，同时是路由键。 */
    static final String NAME = "skill";

    /** 输出里最多列出的附带文件数：太多就退化成一份目录清单，反而挤掉正文。 */
    private static final int MAX_RESOURCE_LINES = 50;

    /** 工具名片。 */
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            NAME,
            "加载指定 skill 的完整说明。可用的名称见 system prompt 里的 skills 清单；"
                    + "加载后按正文的指示工作，正文提到的附带文件用 read_file 或 shell 按需读取。",
            parameters(),
            Arrays.asList("name"),
            true);

    /** skill 目录缓存。 */
    private final SkillCatalog catalog;

    /** 配置：正文读取上限。 */
    private final SkillsConfig config;

    /**
     * 构造工具。
     *
     * @param catalog skill 目录缓存，不可为 {@code null}
     * @param config  配置，不可为 {@code null}
     */
    SkillTool(SkillCatalog catalog, SkillsConfig config) {
        this.catalog = catalog;
        this.config = config;
    }

    /**
     * 获取工具名片。
     *
     * @return 名片，保证非 {@code null}
     */
    ToolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    /**
     * 加载一个 skill 的正文。
     *
     * @param request 工具调用请求，不可为 {@code null}
     * @return 工具结果：正文 + 附带文件指引
     * @throws JellyfishException 参数缺失或 {@code SKILL.md} 读取失败时抛出
     */
    @Override
    public ToolCallResult handle(ToolCallRequest request) {
        String name = requireName(request.getArguments());
        SkillScanResult snapshot = catalog.current();
        SkillDefinition skill = snapshot.find(name);
        if (skill == null) {
            // 名称写错是模型自己的事：给一条能自我纠正的错误，而不是让它换个说法再试一次
            return result(notFoundText(name, snapshot.names()), name, 0);
        }
        String body = readBody(skill);
        return result(bodyText(skill, body), skill.name(), skill.bodyBytes());
    }

    /**
     * 组装正文回灌文本：抬头给出名称与目录，正文紧随其后，附带文件列在末尾。
     * <p>
     * <b>目录必须给绝对路径</b>：正文里的相对引用（{@code references/foo.md}、{@code scripts/run.py}）
     * 只有配上目录才落得到实处，而模型拿到的是可能与其它 skill 同名的相对路径。
     *
     * @param skill 定义
     * @param body  正文（可能已截断）
     * @return 回灌文本
     */
    private String bodyText(SkillDefinition skill, String body) {
        StringBuilder text = new StringBuilder();
        text.append("[skill: ").append(skill.name()).append("]（目录：")
                .append(skill.directory()).append("）\n").append(body);
        List<String> resources = skill.resources();
        if (!resources.isEmpty()) {
            text.append("\n\n附带文件（用 read_file 或 shell 按需读取）：");
            int listed = 0;
            for (String resource : resources) {
                if (listed >= MAX_RESOURCE_LINES) {
                    break;
                }
                text.append("\n- ").append(resource);
                listed++;
            }
            int omitted = resources.size() - listed;
            if (omitted > 0 || skill.resourcesTruncated()) {
                text.append("\n（另有 ").append(omitted > 0 ? omitted : "若干").append(" 个未列出）");
            }
        }
        return text.toString();
    }

    /**
     * 读取正文，超过上限时按字符边界截断。
     * <p>
     * <b>截断要退到字符首字节</b>：直接按字节切一个 UTF-8 字符串，末尾会留下半个多字节字符，
     * 解码出替换符。它不影响模型理解，但会让「文件被截断」这件事看起来像编码问题。
     *
     * @param skill 定义
     * @return 正文；超限时带一条截断说明
     * @throws JellyfishException 读取失败时抛出
     */
    private String readBody(SkillDefinition skill) {
        String content;
        try {
            content = new String(Files.readAllBytes(skill.bodyPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JellyfishException("读取 skill 正文失败: " + skill.bodyPath()
                    + "（" + e.getMessage() + "）", e);
        }
        String body = SkillFrontmatter.parse(content).body();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        int limit = config.maxBodyBytes();
        if (bytes.length <= limit) {
            return body;
        }
        int end = limit;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8)
                + "\n\n…（正文超过 " + limit + " 字节，已截断；完整内容见 " + skill.bodyPath() + "）";
    }

    /**
     * 组装「没找到」的回灌文本，列出可用名称。
     *
     * @param name  请求的名称
     * @param names 当前可用名称
     * @return 回灌文本
     */
    private static String notFoundText(String name, List<String> names) {
        if (names.isEmpty()) {
            return "没有名为 " + name + " 的 skill，当前也没有任何可用的 skill。";
        }
        return "没有名为 " + name + " 的 skill。可用：" + String.join("、", names);
    }

    /**
     * 组装工具结果，带上「这一行到底是什么事」的摘要。
     *
     * @param output    回灌文本
     * @param name      skill 名称
     * @param bodyBytes 正文字节数；{@code 0} 表示未加载成功，不带体积信息
     * @return 工具结果
     */
    private static ToolCallResult result(String output, String name, int bodyBytes) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put(ToolMetadata.KEY_SUMMARY, bodyBytes > 0
                ? NAME + " " + name + " · " + SkillText.humanBytes(bodyBytes)
                : NAME + " " + name + " · 未加载");
        return new ToolCallResult(NAME, output, metadata);
    }

    /**
     * 读取必需的名称参数。
     *
     * @param arguments 参数映射，可为 {@code null}
     * @return 非空名称
     * @throws JellyfishException 缺失、类型不符或为空白时抛出
     */
    private static String requireName(Map<String, Object> arguments) {
        Object raw = arguments == null ? null : arguments.get("name");
        if (raw == null) {
            throw new JellyfishException("缺少必需参数: name");
        }
        if (!(raw instanceof String)) {
            throw new JellyfishException("参数 name 必须是字符串，实际是 " + raw);
        }
        String name = ((String) raw).trim();
        if (name.isEmpty()) {
            throw new JellyfishException("参数 name 不能为空");
        }
        return name;
    }

    /**
     * 构造工具参数 Schema。
     *
     * @return properties 映射
     */
    private static Map<String, Object> parameters() {
        Map<String, Object> nameSchema = new LinkedHashMap<String, Object>();
        nameSchema.put("type", "string");
        nameSchema.put("description", "skill 名称，取 system prompt 清单里的那个名字");
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("name", nameSchema);
        return properties;
    }
}
