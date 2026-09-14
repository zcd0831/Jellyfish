package zcd.jellyfish.tui;

import zcd.jellyfish.infra.session.Session;
import zcd.jellyfish.infra.session.SessionUsage;

/**
 * 状态栏：把会话运行态压成一行文本。
 * <p>
 * <b>为什么是纯函数式的取文本而不是直接画元素</b>：状态栏要显示的字段全部来自 {@link Session}，
 * 而会话是长期存在、随时可被命令改写的运行态（{@code /model}、{@code /agent}、{@code /mode} 都改它）。
 * 因此这里每次渲染都<b>现读</b>会话，而不是把字段缓存在界面对象里——缓存下来就会出现
 * 「换了模型，状态栏还写着旧的」。这与外壳「每轮现读当前会话」的既有口径是同一条规则。
 *
 * @author zcd
 */
public final class StatusBarView {

    /** 未知值占位。 */
    private static final String UNKNOWN = "-";

    /** 字段分隔符。 */
    private static final String SEPARATOR = " \u00b7 ";

    private StatusBarView() {
    }

    /**
     * 渲染状态栏文本。
     *
     * @param session     当前会话，可为 {@code null}
     * @param fallbackAgent 会话未绑定 agent 时显示的兜底 agentId，可为 {@code null}
     * @param contextLength 当前模型上下文长度，未知时为 0 或负数
     * @return 状态栏文本，保证非 {@code null}
     */
    public static String render(Session session, String fallbackAgent, int contextLength) {
        if (session == null) {
            return UNKNOWN;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(" ").append(orUnknown(session.getAgentId() != null ? session.getAgentId() : fallbackAgent));
        sb.append(SEPARATOR).append(modelLabel(session));
        sb.append(SEPARATOR).append(session.getPermissionMode() == null
                ? UNKNOWN : session.getPermissionMode().name().toLowerCase());
        sb.append(SEPARATOR).append(usageLabel(session.getUsage(), contextLength));
        return sb.toString();
    }

    /**
     * 生成 provider/model 标签。
     *
     * @param session 会话
     * @return 标签；会话未指定时返回 {@code 默认}
     */
    private static String modelLabel(Session session) {
        String provider = session.getProvider();
        String model = session.getModel();
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
     * 生成 token 用量标签。
     *
     * @param usage         会话用量，可为 {@code null}
     * @param contextLength 上下文长度，未知时为 0 或负数
     * @return 标签，形如 {@code 12.4k/200k}；无用量时返回 {@code 0}
     */
    private static String usageLabel(SessionUsage usage, int contextLength) {
        long total = usage == null ? 0L : usage.getTotalTokens();
        if (contextLength <= 0) {
            return abbreviate(total);
        }
        return abbreviate(total) + "/" + abbreviate(contextLength);
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
}
