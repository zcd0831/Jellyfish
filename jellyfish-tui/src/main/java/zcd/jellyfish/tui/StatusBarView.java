package zcd.jellyfish.tui;

import zcd.jellyfish.api.extension.PermissionMode;
import zcd.jellyfish.infra.session.SessionUsage;
import zcd.jellyfish.tui.text.DisplayWidth;

import java.util.List;

/**
 * 状态栏：把会话运行态压成一行文本。
 * <p>
 * <b>为什么是纯函数式的取文本而不是直接画元素</b>：状态栏要显示的字段全部来自会话与外壳运行态，
 * 这些状态是长期存在、随时可被命令改写的（{@code /model}、{@code /agent}、{@code /mode} 都改会话）。
 * 因此外壳每帧<b>现读</b>并装配成 {@link Info}，这里只负责把它渲染成一行——纯函数便于单测，
 * 也不会出现「换了模型，状态栏还写着旧的」这种缓存陈旧问题。
 *
 * @author zcd
 */
public final class StatusBarView {

    /** 未知值占位。 */
    private static final String UNKNOWN = "-";

    /** 字段分隔符。 */
    private static final String SEPARATOR = " \u00b7 ";

    /** 插件片段与内核字段、片段与片段之间的分隔符（比字段分隔符宽松一点，避免与内核字段混淆）。 */
    private static final String FRAGMENT_SEPARATOR = "   ";

    /** 工作目录的显示宽度上限（列）。超过就保留尾部若干段并前缀 {@code …/}。 */
    private static final int MAX_WORKING_DIR_WIDTH = 24;

    /** 路径省略前缀。 */
    private static final String ELLIPSIS = "\u2026";

    private StatusBarView() {
    }

    /**
     * 把插件贡献的状态栏片段追加到已渲染的状态栏文本之后。
     * <p>
     * <b>为什么是「整块丢弃」而不是「截断」</b>：状态栏是一行扫读区域，被切掉一半的片段既读不懂，
     * 又会让人以为插件坏了；丢掉整块至少语义完整。丢弃从<b>最后一个</b>片段开始——它与
     * {@code order} 升序呼应：插件声明 order 越小越靠前，也越不容易被丢掉。
     * <p>
     * 宽度按终端列数算而不是字符数：片段常含中文（{@code 待办 2/5}），按 {@code length()} 算会低估。
     *
     * @param base        已渲染的状态栏文本，可为 {@code null}
     * @param fragments   插件片段，按 {@code order} 升序；可为 {@code null} 或空
     * @param terminalWidth 终端总列数；不大于 0 时不做宽度限制
     * @return 追加后的状态栏文本，保证非 {@code null}
     */
    public static String appendFragments(String base, List<String> fragments, int terminalWidth) {
        String text = base == null ? "" : base;
        if (fragments == null || fragments.isEmpty()) {
            return text;
        }
        StringBuilder buffer = new StringBuilder(text);
        for (String fragment : fragments) {
            if (fragment == null || fragment.isEmpty()) {
                continue;
            }
            String candidate = buffer + FRAGMENT_SEPARATOR + fragment;
            if (terminalWidth > 0 && DisplayWidth.of(candidate) > terminalWidth) {
                break;
            }
            buffer.setLength(0);
            buffer.append(candidate);
        }
        return buffer.toString();
    }

    /**
     * 渲染状态栏文本。
     * <p>
     * 字段顺序固定为：agent · provider/model · 权限模式 · 工作目录 · 上下文 · token 用量。
     * 前段是「我是谁、在哪、用什么模型」，后段是「这轮对话的规模」，便于从左到右扫读。
     *
     * @param info 状态栏数据，可为 {@code null}
     * @return 状态栏文本，保证非 {@code null}
     */
    public static String render(Info info) {
        if (info == null) {
            return UNKNOWN;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(" ").append(orUnknown(info.getAgentId()));
        sb.append(SEPARATOR).append(modelLabel(info.getProvider(), info.getModel()));
        sb.append(SEPARATOR).append(info.getPermissionMode() == null
                ? UNKNOWN : info.getPermissionMode().name().toLowerCase());
        sb.append(SEPARATOR).append(workingDirLabel(info.getWorkingDir()));
        sb.append(SEPARATOR).append(contextLabel(info.getContextTokens(), info.getContextLength()));
        sb.append(SEPARATOR).append(usageLabel(info.getUsage()));
        return sb.toString();
    }

    /**
     * 生成 provider/model 标签。
     *
     * @param provider provider 名，可为 {@code null}
     * @param model    model 名，可为 {@code null}
     * @return 标签；两者都为空时返回 {@code 默认}
     */
    private static String modelLabel(String provider, String model) {
        if (provider == null && model == null) {
            return "默认";
        }
        if (provider == null) {
            return orUnknown(model);
        }
        if (model == null) {
            return provider;
        }
        return provider + "/" + model;
    }

    /**
     * 生成工作目录标签：主目录缩写为 {@code ~}，过长时保留尾部若干段。
     *
     * @param workingDir 工作目录，可为 {@code null}
     * @return 标签；未知时返回 {@code -}
     */
    private static String workingDirLabel(String workingDir) {
        if (workingDir == null || workingDir.isEmpty()) {
            return UNKNOWN;
        }
        String text = abbreviateHome(workingDir);
        if (DisplayWidth.of(text) <= MAX_WORKING_DIR_WIDTH) {
            return text;
        }
        return ellipsizePath(text);
    }

    /**
     * 把路径行首的用户主目录换成 {@code ~}。
     *
     * @param path 绝对路径
     * @return 缩写后的路径；不含主目录前缀时原样返回
     */
    private static String abbreviateHome(String path) {
        String home = System.getProperty("user.home");
        if (home == null || home.isEmpty() || !path.startsWith(home)) {
            return path;
        }
        String rest = path.substring(home.length());
        if (rest.isEmpty()) {
            return "~";
        }
        if (rest.charAt(0) != '/' && rest.charAt(0) != '\\') {
            // 前缀撞车（例如 /home/user2 与 /home/user）：不缩写，以免显示成误导性的路径
            return path;
        }
        return "~" + rest;
    }

    /**
     * 路径过长时只保留尾部若干段，前缀 {@code …/}。
     * <p>
     * 从右往左逐段累加，直到再加一段就超宽为止：尾部才是区分当前项目的部分，
     * 被砍掉的应当是前面的父目录。
     *
     * @param path 已缩写主目录的路径
     * @return 省略后的路径；连最后一段都放不下时只返回省略前缀
     */
    private static String ellipsizePath(String path) {
        char separator = path.indexOf('\\') >= 0 && path.indexOf('/') < 0 ? '\\' : '/';
        String prefix = ELLIPSIS + separator;
        String[] parts = path.split("[/\\\\]+");
        StringBuilder tail = new StringBuilder();
        for (int i = parts.length - 1; i >= 0; i--) {
            if (parts[i].isEmpty()) {
                continue;
            }
            String candidate = tail.length() == 0 ? parts[i] : parts[i] + separator + tail;
            if (DisplayWidth.of(prefix + candidate) > MAX_WORKING_DIR_WIDTH) {
                break;
            }
            tail.setLength(0);
            tail.append(candidate);
        }
        return tail.length() == 0 ? prefix : prefix + tail;
    }

    /**
     * 生成上下文标签。
     * <p>
     * 分子是<b>最近一次调用厂商返回的输入 token 数</b>（含系统提示词与裁剪后的历史），
     * 而不是会话累计总量——累计总量可以远超上下文窗口，拿它当分子只会让人误判「快爆了」。
     *
     * @param contextTokens 最近一次调用的输入 token 数
     * @param contextLength 模型上下文窗口，未知时为 0 或负数
     * @return 标签，形如 {@code ctx 12.4k/128k}；窗口未知时省略分母
     */
    private static String contextLabel(long contextTokens, int contextLength) {
        if (contextLength <= 0) {
            return "ctx " + abbreviate(contextTokens);
        }
        return "ctx " + abbreviate(contextTokens) + "/" + abbreviate(contextLength);
    }

    /**
     * 生成 token 累计用量标签，按上传 / 下载分列。
     *
     * @param usage 会话用量，可为 {@code null}
     * @return 标签，形如 {@code ↑3.2k ↓1.1k}；无用量时返回 {@code ↑0 ↓0}
     */
    private static String usageLabel(SessionUsage usage) {
        long prompt = usage == null ? 0L : usage.getPromptTokens();
        long completion = usage == null ? 0L : usage.getCompletionTokens();
        return "\u2191" + abbreviate(prompt) + " \u2193" + abbreviate(completion);
    }

    /**
     * 把 token 数缩写成便于扫读的形式。
     *
     * @param value 数值
     * @return 缩写文本
     */
    private static String abbreviate(long value) {
        if (value < 1000L) {
            return Long.toString(value);
        }
        if (value < 1_000_000L) {
            return oneDecimal(value / 1000.0) + "k";
        }
        return oneDecimal(value / 1_000_000.0) + "m";
    }

    /**
     * 保留一位小数。
     *
     * @param value 数值
     * @return 文本，恰好整数时不带小数点
     */
    private static String oneDecimal(double value) {
        long scaled = Math.round(value * 10.0);
        long whole = scaled / 10L;
        long fraction = Math.abs(scaled % 10L);
        return fraction == 0L ? Long.toString(whole) : whole + "." + fraction;
    }

    /**
     * 空值兜底。
     *
     * @param value 值，可为 {@code null}
     * @return 原值或 {@code -}
     */
    private static String orUnknown(String value) {
        return value == null || value.isEmpty() ? UNKNOWN : value;
    }

    /**
     * 状态栏数据：外壳每帧从会话运行态与模型门面现读出来的字段集合。
     * <p>
     * <b>为什么单独抽一层而不是让渲染方法直接读 {@link zcd.jellyfish.infra.session.Session}</b>：
     * 「生效模型」「工作目录」「最近一次调用的输入 token」这几项要么需要 {@code ModelManager} 解析，
     * 要么要遍历消息列表，都不属于渲染职责。抽出来之后渲染侧只做字符串拼装，可以纯函数单测。
     * <p>
     * 不可变值对象：所有字段 final、无 setter。
     *
     * @author zcd
     */
    public static final class Info {

        /** 当前 agentId，可为 {@code null}。 */
        private final String agentId;

        /** 当前生效的 provider 名，可为 {@code null}。 */
        private final String provider;

        /** 当前生效的 model 名，可为 {@code null}。 */
        private final String model;

        /** 当前权限模式，可为 {@code null}。 */
        private final PermissionMode permissionMode;

        /** 进程工作目录，可为 {@code null}。 */
        private final String workingDir;

        /** 最近一次调用的输入 token 数。 */
        private final long contextTokens;

        /** 模型上下文窗口，未知时为 0 或负数。 */
        private final int contextLength;

        /** 会话 token 累计用量，可为 {@code null}。 */
        private final SessionUsage usage;

        /**
         * 构造状态栏数据。
         *
         * @param agentId       当前 agentId，可为 {@code null}
         * @param provider      当前生效的 provider 名，可为 {@code null}
         * @param model         当前生效的 model 名，可为 {@code null}
         * @param permissionMode 当前权限模式，可为 {@code null}
         * @param workingDir    进程工作目录，可为 {@code null}
         * @param contextTokens 最近一次调用的输入 token 数
         * @param contextLength 模型上下文窗口，未知时为 0 或负数
         * @param usage         会话 token 累计用量，可为 {@code null}
         */
        public Info(String agentId, String provider, String model, PermissionMode permissionMode,
                    String workingDir, long contextTokens, int contextLength, SessionUsage usage) {
            this.agentId = agentId;
            this.provider = provider;
            this.model = model;
            this.permissionMode = permissionMode;
            this.workingDir = workingDir;
            this.contextTokens = contextTokens;
            this.contextLength = contextLength;
            this.usage = usage;
        }

        /**
         * 获取当前 agentId。
         *
         * @return agentId，可为 {@code null}
         */
        public String getAgentId() {
            return agentId;
        }

        /**
         * 获取当前生效的 provider 名。
         *
         * @return provider 名，可为 {@code null}
         */
        public String getProvider() {
            return provider;
        }

        /**
         * 获取当前生效的 model 名。
         *
         * @return model 名，可为 {@code null}
         */
        public String getModel() {
            return model;
        }

        /**
         * 获取当前权限模式。
         *
         * @return 权限模式，可为 {@code null}
         */
        public PermissionMode getPermissionMode() {
            return permissionMode;
        }

        /**
         * 获取进程工作目录。
         *
         * @return 工作目录，可为 {@code null}
         */
        public String getWorkingDir() {
            return workingDir;
        }

        /**
         * 获取最近一次调用的输入 token 数。
         *
         * @return 输入 token 数
         */
        public long getContextTokens() {
            return contextTokens;
        }

        /**
         * 获取模型上下文窗口。
         *
         * @return 上下文窗口，未知时为 0 或负数
         */
        public int getContextLength() {
            return contextLength;
        }

        /**
         * 获取会话 token 累计用量。
         *
         * @return 累计用量，可为 {@code null}
         */
        public SessionUsage getUsage() {
            return usage;
        }
    }
}
