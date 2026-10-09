package zcd.jellyfish.core.input;

/**
 * 一条输入指令结束时的通知：<b>恰好回调一次</b>，无论它是跑完了、失败了还是被取消了。
 * <p>
 * <b>为什么要有它</b>：指令有自己的实时输出，那些事件走可靠 lane（{@code ShellStreams}），
 * 而可靠 lane 的契约是「每个标识的事件流恰好一条终态」。指令此前只有中间事件、没有终态——
 * 按契约写的订阅者（回合事件一到就判 {@code isTerminal}）会一直等一个永远不会来的事件。
 * 这个回调就是那条终态的出处，它由<b>提交方</b>在提交执行之前交进来（见
 * {@link InputDirectives#submit}），因此不存在「指令跑得太快、注册晚了就漏掉」的窗口。
 * <p>
 * <b>它只被调用一次，且可能在你拿到句柄之前就被调用</b>：指令是异步的，短到在你从
 * {@code submit} 返回之前就已经跑完，因此回调里不要假设「此时调用方已经持有句柄并做完初始化」。
 * <p>
 * <b>它说什么、不说什么</b>：回调只负责宣告「这条事件流结束了」（指令是否被取消由
 * {@link InputDirectiveRun#isCancelled()} 读取）；<b>失败原因不在这里</b>——指令的结果与失败说明
 * 已经作为一条会话消息落库并由外壳展示，在这里再说一遍只会让同一件事在屏幕上出现两次。
 * <p>
 * <b>回调抛错不会被上抛</b>：它只是「通知」，通知失败不该改变指令本身的结局（内核记一条 WARN）。
 *
 * @author zcd
 */
@FunctionalInterface
public interface InputDirectiveCompletion {

    /**
     * 指令已结束。
     *
     * @param run 结束的指令句柄，不可为 {@code null}；此刻它一定已经结束（{@code isDone()} 为真）
     */
    void finished(InputDirectiveRun run);
}
