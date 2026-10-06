package zcd.jellyfish.api.ask;

import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次「向用户提问」请求：问什么、给哪些选项、替谁问。
 * <p>
 * <b>{@link #getSource()} 是给界面与审计看的</b>：它说明这个提问框是谁弹出来的
 * （工具名下划线形式，如 {@code ask_user}、{@code plan_confirm}）。内核据此渲染标题、写日志，
 * 因此提问框出现时用户能知道「现在是谁在问我」。它不参与任何路由或权限判定。
 * <p>
 * <b>选项至少两个</b>：一个选项的问题没有提问的意义（用户没有可比较的备选），而且
 * 「唯一选项」的界面与「确认」按钮无异，会让用户误以为这是一次审批。构造函数在选项少于两个时
 * 抛 {@link JellyfishException}，是调用方的编程错误，不是运行期条件。
 * <p>
 * <b>没有自由文本字段</b>：是否允许用户自己输入由外壳决定（TUI 追加一项「其它」并开一个编辑态），
 * 内核不为此设开关——提问方要的是「一个答案」，答案的形式不是它该管的事。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class AskRequest {

    /** 发起提问的来源（工具名等），供界面与日志使用。 */
    private final String source;

    /** 目标会话标识，可为 {@code null}。 */
    private final String sessionId;

    /** 要问的问题原文。 */
    private final String question;

    /** 候选项，只读且至少两项。 */
    private final List<AskOption> options;

    /**
     * 构造提问请求。
     * <p>
     * 跨边界值类型只有这一个可见构造器；日常构造请用静态工厂。
     *
     * @param source    发起提问的来源，不可为空白
     * @param sessionId 目标会话标识，可为 {@code null}
     * @param question  要问的问题原文，不可为空白
     * @param options   候选项，不可为 {@code null} 且至少两项
     * @throws JellyfishException 来源或问题为空白、选项少于两项时抛出
     */
    public AskRequest(String source, String sessionId, String question, List<AskOption> options) {
        if (source == null || source.trim().isEmpty()) {
            throw new JellyfishException("ask source must not be blank");
        }
        if (question == null || question.trim().isEmpty()) {
            throw new JellyfishException("ask question must not be blank");
        }
        if (options == null || options.size() < 2) {
            throw new JellyfishException("ask request requires at least 2 options");
        }
        this.source = source;
        this.sessionId = sessionId;
        this.question = question;
        this.options = Collections.unmodifiableList(new ArrayList<AskOption>(options));
    }

    /**
     * 构造提问请求。
     *
     * @param source    发起提问的来源，不可为空白
     * @param sessionId 目标会话标识，可为 {@code null}
     * @param question  要问的问题原文，不可为空白
     * @param options   候选项，不可为 {@code null} 且至少两项
     * @return 提问请求，保证非 {@code null}
     */
    public static AskRequest of(String source, String sessionId, String question, List<AskOption> options) {
        return new AskRequest(source, sessionId, question, options);
    }

    /**
     * 获取发起提问的来源。
     *
     * @return 来源，保证非 {@code null}
     */
    public String getSource() {
        return source;
    }

    /**
     * 获取目标会话标识。
     *
     * @return 会话标识，可能为 {@code null}
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取要问的问题原文。
     *
     * @return 问题原文，保证非 {@code null}
     */
    public String getQuestion() {
        return question;
    }

    /**
     * 获取候选项。
     *
     * @return 不可变列表，至少两项，保证非 {@code null}
     */
    public List<AskOption> getOptions() {
        return options;
    }

    /**
     * 按选项标识查找候选项。
     * <p>
     * 供外壳把回传的答案折回一个具体选项（界面要显示「用户选了哪一项」）。
     *
     * @param optionId 选项标识，可为 {@code null}
     * @return 命中的候选项；没有命中时返回 {@code null}
     */
    public AskOption option(String optionId) {
        if (optionId == null) {
            return null;
        }
        for (AskOption option : options) {
            if (optionId.equals(option.getOptionId())) {
                return option;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "AskRequest{source=" + source + ", sessionId=" + sessionId + ", options=" + options.size() + '}';
    }
}
