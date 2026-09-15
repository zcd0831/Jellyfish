package zcd.jellyfish.tui;

/**
 * 启动提示：新会话页面上由外壳先贴出的第一条消息，告诉用户基本用法。
 * <p>
 * <b>为什么不进会话也不经过 LLM</b>：它若落进会话，会被后续每一轮请求当作历史发给模型，
 * 既污染 prompt 又让模型以为自己说过这段话。因此它只是外壳的一份渲染态，
 * 参与投影、随消息滚走，但不进会话。
 * <p>
 * <b>为什么不是外壳提示</b>：外壳提示（{@link ShellNotice}）带 {@code ⎿} 块前缀与命令原文回显，
 * 那是「用户主动要的命令结果」的视觉；启动提示是外壳以 agent 身份先说的话，
 * 因此由 {@link ChatState} 持有并在投影时复用助手消息的样子（{@code ⏺ jellyfish} 表头 + 正文缩进，
 * 见 {@link TranscriptProjector}）。
 * <p>
 * <b>文案是契约</b>：键位取自 {@link InputKeyMapper} 的判定，命令入口取自 {@link ShellCommand}
 * 与命令域，二者一旦改动，这里必须同步——所以文案集中在本类，不散落在各处。
 *
 * @author zcd
 */
public final class StartupHint {

    /**
     * 启动提示文案。
     * <p>
     * 首行以助手口吻开场（真正显示时它就在 {@code ⏺ jellyfish} 表头之下），其余各行给出键位与入口。
     * 键位采用 T5 的反转方案：{@code Ctrl+S} 发送、{@code Enter} 换行（见 {@link InputKeyMapper}）。
     */
    private static final String TEXT = "你好，我是你的终端助手，先介绍一下基本用法：\n"
            + "\n"
            + "Ctrl+S 发送 · Enter 换行 · Esc 中断 · Ctrl+C 退出\n"
            + "输入 / 唤起命令补全 · /help 查看全部命令\n"
            + "PageUp / PageDown 或滚轮滚动消息区\n"
            + "直接输入内容即可开始对话，也可以问我怎么使用。";

    private StartupHint() {
    }

    /**
     * 获取启动提示文案。
     *
     * @return 启动提示文案，保证非 {@code null}
     */
    public static String text() {
        return TEXT;
    }
}
