package zcd.jellyfish.tui;

/**
 * 按键动作：外壳对一次按键的意图判定结果。
 * <p>
 * 引入这一层是为了把「哪个键是什么意思」与「动作怎么执行」分开：
 * 判定是纯函数、可单测；执行需要终端与会话，只能端到端验证。
 *
 * @author zcd
 */
public enum InputAction {

    /** 发送当前输入。 */
    SEND,

    /** 中断进行中的回合。 */
    CANCEL,

    /** 退出外壳。 */
    QUIT,

    /** 消息区上翻一页。 */
    PAGE_UP,

    /** 消息区下翻一页。 */
    PAGE_DOWN,

    /**
     * 消息区向上滚动若干行（滚轮一格）。
     * <p>
     * 与 {@link #PAGE_UP} 的区别只在步长：翻页按可见行数减一，滚轮按
     * {@link MouseScrollMapper#WHEEL_STEP_ROWS}。两者都归 {@link ChatState} 的滚动状态管，
     * 因此跟随/回底语义完全一致。
     */
    SCROLL_UP,

    /** 消息区向下滚动若干行（滚轮一格）。 */
    SCROLL_DOWN,

    /** 消息区跳到末尾并恢复跟随。 */
    TO_BOTTOM,

    /** 补全候选上移一项（仅补全面板可见时归外壳管）。 */
    COMPLETE_PREV,

    /** 补全候选下移一项（仅补全面板可见时归外壳管）。 */
    COMPLETE_NEXT,

    /**
     * 确认当前浮层面板的选中项（仅面板可见时归外壳管）。
     * <p>
     * {@code Enter} 映射到这里。补全面板可见时意为「把选中命令回填进输入框」，
     * 二级选择页可见时意为「执行选中的取值」；面板没弹时外壳返回 {@code UNHANDLED}，
     * 它就落回输入框当换行（T5 的反转键位不受影响）。
     */
    COMPLETE_ACCEPT,

    /**
     * 不归外壳管，交给输入框编辑。
     * <p>
     * {@code Enter} 属于这一类——T5 采用反转键位，它是换行键而不是发送键。
     */
    EDIT
}
