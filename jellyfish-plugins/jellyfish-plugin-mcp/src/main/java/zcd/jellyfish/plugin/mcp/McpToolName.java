package zcd.jellyfish.plugin.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * MCP 工具名在内核里的展开规则：{@code mcp__<server>__<tool>}。
 * <p>
 * <b>为什么必须加前缀</b>：工具名由 server 决定，而 server 之间、server 与内置工具之间完全可能重名
 * （{@code read_file} 就是典型）。不加前缀等于让「谁先连上谁占坑」成为事实，而那种冲突的表现是
 * 「某个工具忽然变成了别人的」——最难排查的一类问题。
 * <p>
 * <b>为什么必须清洗字符</b>：各厂商对 function name 有共同约束（字母、数字、下划线、连字符），
 * 而 MCP 的工具名允许点号与其它字符。带点号的工具名会被厂商在整次请求上拒绝——不是「这个工具不可用」，
 * 是「这一轮对话发不出去」。
 * <p>
 * <b>为什么超长要保留可读前缀并追加哈希</b>：直接截断会让 {@code ..._create_issue} 与
 * {@code ..._create_issue_v2} 变成同一个名字（后注册的那个变成幽灵）。保留前缀让日志里一眼能看出
 * 是哪个 server 的哪个工具，哈希保证截断之后仍然唯一。
 * <p>
 * 不能实例化。
 *
 * @author zcd
 */
final class McpToolName {

    /** 工具名前缀。 */
    static final String PREFIX = "mcp__";

    /** 服务标识与工具名之间的分隔符。 */
    static final String SERVER_SEPARATOR = "__";

    /** 截断时追加的哈希长度。 */
    private static final int HASH_LENGTH = 8;

    /**
     * 工具类，禁止实例化。
     */
    private McpToolName() {
    }

    /**
     * 展开一个工具名。
     *
     * @param serverId  服务标识
     * @param toolName  server 给的原始工具名
     * @param prefixed  是否加 {@code mcp__<server>__} 前缀
     * @param maxLength 长度上限
     * @return 内核里使用的工具名
     */
    static String qualify(String serverId, String toolName, boolean prefixed, int maxLength) {
        String body = sanitize(toolName);
        String candidate = prefixed ? PREFIX + sanitize(serverId) + SERVER_SEPARATOR + body : body;
        if (candidate.length() <= maxLength) {
            return candidate;
        }
        String hash = shortHash(candidate);
        return candidate.substring(0, maxLength - HASH_LENGTH - 1) + "_" + hash;
    }

    /**
     * 判断一个工具名是否由本插件提供。
     * <p>
     * 仅在前缀开关打开时有意义；关闭前缀时无法（也不应）凭名字判断归属，
     * 因此权限拦截那一侧用的是「注册表里有没有这个名字」而不是前缀匹配。
     *
     * @param toolName 工具名，可为 {@code null}
     * @return 由本插件提供返回 {@code true}
     */
    static boolean isMcpTool(String toolName) {
        return toolName != null && toolName.startsWith(PREFIX);
    }

    /**
     * 清洗工具名：厂商允许的字符保留，其余一律换成下划线。
     *
     * @param raw 原始名字，可为 {@code null}
     * @return 清洗后的名字；输入为 {@code null} 或空白时返回 {@code tool}
     */
    static String sanitize(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "tool";
        }
        StringBuilder sanitized = new StringBuilder(raw.length());
        for (int index = 0; index < raw.length(); index++) {
            char current = raw.charAt(index);
            boolean allowed = (current >= 'a' && current <= 'z') || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9') || current == '_' || current == '-';
            sanitized.append(allowed ? current : '_');
        }
        return sanitized.toString();
    }

    /**
     * 取名字的前若干位哈希。
     *
     * @param value 原始文本
     * @return 十六进制哈希片段
     */
    static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(HASH_LENGTH);
            for (int index = 0; index < HASH_LENGTH / 2; index++) {
                hex.append(String.format("%02x", Byte.valueOf(bytes[index])));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须提供的算法，取不到说明运行环境本身不可用
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
