package zcd.jellyfish.api.extension;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 参数改写裁定：插件在 {@link ToolArgumentPreRequest} 上能表达的结果。
 * <p>
 * <b>为什么是裁定而不是「返回一份新参数」</b>：只有「返回新参数」一种表达能力时，插件无法区分
 * 「我看过了、不改」与「我没看」——而这两者对后面的处理器是不同的：前者之后还有别的插件，
 * 后者会让整条链的语义变成「谁最后返回谁说了算」。三态把「不表态」显式写出来，链式传递因此有明确规则。
 * <p>
 * <b>三态的含义</b>：
 * <ul>
 *     <li>{@link Outcome#ABSTAIN}：不改，把当前值原样交给下一个处理器；</li>
 *     <li>{@link Outcome#REPLACE}：用 {@link #getArguments()} 替换当前值，继续交给下一个处理器；</li>
 *     <li>{@link Outcome#DENY}：短路，本次调用直接产出一条失败结果，后面的处理器不再被调用。</li>
 * </ul>
 * <p>
 * <b>没有「放行」这一态</b>：本扩展点表达不了权限结论，参数改写之后照旧要过
 * {@code PermissionCheckRequest}。{@code DENY} 只是「这条参数我不同意」，与权限拒绝是两件事
 * （因此它也不进权限审计）。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ToolArgumentDecision {

    /** 裁定三态。 */
    public enum Outcome {
        /** 不改，把当前值交给下一个处理器。 */
        ABSTAIN,
        /** 替换参数，把新值交给下一个处理器。 */
        REPLACE,
        /** 拒绝本次调用：短路并产出一条失败结果。 */
        DENY
    }

    /** 单例：不改。 */
    private static final ToolArgumentDecision ABSTAIN = new ToolArgumentDecision(Outcome.ABSTAIN, null, null);

    /** 裁定结论。 */
    private final Outcome outcome;

    /** 替换后的参数，仅 {@link Outcome#REPLACE} 时有意义。 */
    private final Map<String, Object> arguments;

    /** 拒绝理由，仅 {@link Outcome#DENY} 时有意义。 */
    private final String reason;

    /**
     * 构造裁定。
     * <p>
     * 参数做了防御性拷贝：插件在返回之后再改自己那份映射，不会影响已经交出去的裁定。
     *
     * @param outcome   裁定结论，不可为 {@code null}
     * @param arguments 替换后的参数，可为 {@code null}（等价空参数）
     * @param reason    拒绝理由，可为 {@code null}
     */
    public ToolArgumentDecision(Outcome outcome, Map<String, Object> arguments, String reason) {
        this.outcome = outcome == null ? Outcome.ABSTAIN : outcome;
        this.arguments = arguments == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(arguments));
        this.reason = reason;
    }

    /**
     * 构造「不改」裁定。
     *
     * @return 不改裁定
     */
    public static ToolArgumentDecision abstain() {
        return ABSTAIN;
    }

    /**
     * 构造「替换参数」裁定。
     * <p>
     * <b>内核不校验新参数的形状</b>：它没有工具的参数 schema，因此写 {@code REPLACE} 的插件
     * 自己保证参数合法——形状不对的后果由工具自己承担（通常是它自己抛参数错误）。
     *
     * @param arguments 替换后的参数，可为 {@code null}（等价空参数）
     * @return 替换裁定
     */
    public static ToolArgumentDecision replace(Map<String, Object> arguments) {
        return new ToolArgumentDecision(Outcome.REPLACE, arguments, null);
    }

    /**
     * 构造「拒绝本次调用」裁定。
     *
     * @param reason 拒绝理由，可为 {@code null}（回灌文本里写固定占位）
     * @return 拒绝裁定
     */
    public static ToolArgumentDecision deny(String reason) {
        return new ToolArgumentDecision(Outcome.DENY, null, reason);
    }

    /**
     * 获取裁定结论。
     *
     * @return 裁定结论，保证非 {@code null}
     */
    public Outcome getOutcome() {
        return outcome;
    }

    /**
     * 判断是否不改。
     *
     * @return 不改返回 {@code true}
     */
    public boolean isAbstain() {
        return outcome == Outcome.ABSTAIN;
    }

    /**
     * 判断是否替换参数。
     *
     * @return 替换返回 {@code true}
     */
    public boolean isReplace() {
        return outcome == Outcome.REPLACE;
    }

    /**
     * 判断是否拒绝。
     *
     * @return 拒绝返回 {@code true}
     */
    public boolean isDenied() {
        return outcome == Outcome.DENY;
    }

    /**
     * 获取替换后的参数。
     *
     * @return 只读参数映射；非替换裁定时为空映射，保证非 {@code null}
     */
    public Map<String, Object> getArguments() {
        return arguments;
    }

    /**
     * 获取拒绝理由。
     *
     * @return 拒绝理由，非拒绝裁定时为 {@code null}
     */
    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return "ToolArgumentDecision{outcome=" + outcome + ", reason=" + reason + '}';
    }
}
