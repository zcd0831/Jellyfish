package zcd.jellyfish.api.extension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 行内引用结果：处理器给出的候选清单。
 * <p>
 * <b>空清单是合法答案</b>：片段没有匹配项时返回 {@link #empty()}，外壳显示「无匹配」占位行，
 * 而不是让面板消失——消失会让用户以为按键没生效。
 * <p>
 * 顺序即显示顺序，内核不重排：插件最清楚什么该排在前面（例如目录先于文件）。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class InputReferenceResult {

    /** 空候选：片段没有匹配项。 */
    private static final InputReferenceResult EMPTY = new InputReferenceResult(null);

    /** 候选清单，保证非 {@code null}。 */
    private final List<InputReferenceChoice> choices;

    /**
     * 构造结果。
     *
     * @param choices 候选清单，可为 {@code null} 或空
     */
    private InputReferenceResult(List<InputReferenceChoice> choices) {
        this.choices = choices == null || choices.isEmpty()
                ? Collections.<InputReferenceChoice>emptyList()
                : Collections.unmodifiableList(new ArrayList<InputReferenceChoice>(choices));
    }

    /**
     * 构造带候选的结果。
     *
     * @param choices 候选清单，可为 {@code null} 或空（等价 {@link #empty()}）
     * @return 引用结果，保证非 {@code null}
     */
    public static InputReferenceResult of(List<InputReferenceChoice> choices) {
        return choices == null || choices.isEmpty() ? EMPTY : new InputReferenceResult(choices);
    }

    /**
     * 获取空候选结果。
     *
     * @return 没有任何候选的结果
     */
    public static InputReferenceResult empty() {
        return EMPTY;
    }

    /**
     * 获取候选清单。
     *
     * @return 不可变候选清单，保证非 {@code null}
     */
    public List<InputReferenceChoice> getChoices() {
        return choices;
    }

    @Override
    public String toString() {
        return "InputReferenceResult{choices=" + choices.size() + '}';
    }
}
