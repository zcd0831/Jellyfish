package zcd.jellyfish.tui;

import zcd.jellyfish.api.extension.InputReferenceChoice;
import zcd.jellyfish.core.input.InputReferenceCompletion;

import java.util.Collections;
import java.util.List;

/**
 * 行内引用补全的状态：把内核算出的 {@link InputReferenceCompletion} 变成「弹不弹、选中第几条、
 * 接受后输入框变成什么」。
 * <p>
 * <b>纯逻辑、无渲染、无终端</b>：与 {@link CommandCompletion} 同构，渲染交给
 * {@link InputReferenceCompletionView}，按键交给 {@link InputKeyMapper} 与 {@link TuiApp}。
 * 这样「片段记忆、选中环回、接受后关闭」这些最容易出错的规则可以脱离终端单测。
 * <p>
 * <b>与命令补全的分工</b>：命令补全是「行首 {@code /} + 命令名」，本类是「行内 {@code @} + 路径片段」。
 * 两者互斥地占用同一块浮层位置（{@code /} 与 {@code @} 不会同时位于一个片段里），
 * 但状态是两个对象：命令补全要记住「被 Esc 关掉的命令词」，本类要记住「被接受的文件名」，
 * 合成一个对象会让两套记忆互相污染。
 * <p>
 * <b>线程契约</b>：与 {@link ChatState} 相同，只由渲染线程读写。
 *
 * @author zcd
 */
public final class InputReferenceCompletionState {

    /** 面板最多显示的行数：与命令补全同口径，补全只是临时浮层。 */
    static final int MAX_VISIBLE = 8;

    /** 是否处于补全态。 */
    private boolean active;

    /** 最近一次补全结果，保证非 {@code null}。 */
    private InputReferenceCompletion completion = InputReferenceCompletion.empty();

    /** 当前候选，保证非 {@code null}。 */
    private List<InputReferenceChoice> candidates = Collections.emptyList();

    /** 当前选中项下标。 */
    private int selected;

    /** 上一次计算时的片段（标记 + 已输入内容），未处于补全上下文时为 {@code null}。 */
    private String token;

    /**
     * 被 {@link #dismiss()} 或「接受了非目录候选」关掉的片段：片段未变化则不再重开。
     * <p>
     * 这是「接受一个文件后面板自动收起」的实现方式——{@code @main.py} 敲全了还弹一个单行候选，
     * 只会让人以为没接受成功。
     */
    private String dismissedToken;

    /**
     * 按最新补全结果重算状态。
     * <p>
     * 每帧调用；{@code fresh} 由内核算好（含替换区间），这里只做状态机。
     *
     * @param input 输入框原文，可为 {@code null}
     * @param fresh 内核给出的补全结果，可为 {@code null}（等价未命中）
     */
    public void refresh(String input, InputReferenceCompletion fresh) {
        if (fresh == null || !fresh.isPresent()) {
            reset();
            dismissedToken = null;
            return;
        }
        String text = input == null ? "" : input;
        int start = Math.max(0, Math.min(fresh.getReplaceStart(), text.length()));
        int end = Math.max(start, Math.min(fresh.getReplaceEnd(), text.length()));
        String currentToken = text.substring(start, end);
        if (currentToken.equals(dismissedToken)) {
            active = false;
            candidates = Collections.emptyList();
            selected = 0;
            completion = fresh;
            token = currentToken;
            return;
        }
        dismissedToken = null;
        if (!currentToken.equals(token)) {
            // 片段变了，旧的选中位置不再有意义
            selected = 0;
        }
        token = currentToken;
        completion = fresh;
        candidates = fresh.getChoices();
        active = true;
        if (selected >= candidates.size()) {
            selected = candidates.isEmpty() ? 0 : candidates.size() - 1;
        }
    }

    /**
     * 判断当前是否应显示补全面板。
     *
     * @return 显示返回 {@code true}
     */
    public boolean isActive() {
        return active;
    }

    /**
     * 获取当前候选清单。
     *
     * @return 不可变候选列表，保证非 {@code null}
     */
    public List<InputReferenceChoice> getCandidates() {
        return candidates;
    }

    /**
     * 获取选中项下标。
     *
     * @return 下标；无候选时为 0
     */
    public int getSelectedIndex() {
        return selected;
    }

    /**
     * 获取选中项。
     *
     * @return 选中项；无候选时为 {@code null}
     */
    public InputReferenceChoice selected() {
        return selected < candidates.size() ? candidates.get(selected) : null;
    }

    /**
     * 选中项上移一项，到头后回到末尾。
     */
    public void moveUp() {
        if (candidates.isEmpty()) {
            return;
        }
        selected = (selected - 1 + candidates.size()) % candidates.size();
    }

    /**
     * 选中项下移一项，到尾后回到开头。
     */
    public void moveDown() {
        if (candidates.isEmpty()) {
            return;
        }
        selected = (selected + 1) % candidates.size();
    }

    /**
     * 接受当前选中项，给出替换后的输入文本。
     * <p>
     * <b>目录与文件的处置不同</b>：候选的插入文本以 {@code /} 结尾说明它是目录，接受后不关闭面板
     * ——用户显然是继续往里钻；否则（文件）把新片段记入「已关闭」，让面板自动收起。
     *
     * @param input 当前输入框原文，可为 {@code null}
     * @return 替换后的输入文本；无选中项时返回 {@code null}
     */
    public String accept(String input) {
        InputReferenceChoice choice = selected();
        if (choice == null || !completion.isPresent()) {
            return null;
        }
        String inserted = completion.getMarker() + choice.getInsertText();
        String text = input == null ? "" : input;
        int start = Math.max(0, Math.min(completion.getReplaceStart(), text.length()));
        int end = Math.max(start, Math.min(completion.getReplaceEnd(), text.length()));
        if (!choice.getInsertText().endsWith("/")) {
            dismissToken(inserted);
        }
        return text.substring(0, start) + inserted + text.substring(end);
    }

    /**
     * 收起面板，并在当前片段上留下「已关闭」记忆。
     * <p>
     * 用户按 {@code Esc} 时调用；只要片段不再变化，{@link #refresh} 就不会重新弹开。
     */
    public void dismiss() {
        if (active) {
            dismissedToken = token;
        }
        active = false;
        candidates = Collections.emptyList();
        selected = 0;
    }

    /**
     * 记住「这个片段已关闭」，面板立刻收起。
     *
     * @param segment 新的片段（标记 + 插入内容），可为 {@code null}
     */
    private void dismissToken(String segment) {
        dismissedToken = segment;
        token = segment;
        active = false;
        candidates = Collections.emptyList();
        selected = 0;
    }

    /**
     * 清空补全状态。
     * <p>
     * 刻意不动 {@link #dismissedToken}：它的生命周期是「一个片段」，由 {@link #refresh} 统一管理。
     */
    private void reset() {
        active = false;
        candidates = Collections.emptyList();
        selected = 0;
        completion = InputReferenceCompletion.empty();
        token = null;
    }
}
