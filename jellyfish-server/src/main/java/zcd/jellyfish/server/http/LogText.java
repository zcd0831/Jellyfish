package zcd.jellyfish.server.http;

import zcd.jellyfish.infra.support.ControlChars;

/**
 * 日志文本的准备：把来自请求的原文压成一行、限个长度，再交给日志。
 * <p>
 * <b>为什么需要它</b>：错误响应的文案不回显请求原文（原文只进日志），但日志同样不能直接收任意文本
 * ——一段带 {@code \n} 的输入会在日志里伪造出一行（{@code 会话不存在} 之后跟一行 {@code 已授权}），
 * 带 {@code ESC} 的输入在终端里翻页或改标题，几 MB 的输入则把上下文挤走。这三件事都不是
 * 「日志写得不好看」，而是「看日志的人读到的东西与真正发生的事不是一回事」。
 * <p>
 * 过滤规则复用 {@link ControlChars#strip}（与终端侧同一份，避免两处规则漂移），再单独把换行换成
 * 空格：日志的语义单位是一行，所以换行在这里不是排版语义，而是必须消掉的分隔符。
 * <p>
 * 本类无状态，全部为静态方法。
 *
 * @author zcd
 */
public final class LogText {

    /** 单条日志里原文的长度上限：够看清是什么，又不至于把日志刷走。 */
    private static final int MAX_CHARS = 200;

    /**
     * 工具类，禁止实例化。
     */
    private LogText() {
    }

    /**
     * 把请求原文压成适合写进单行日志的文本。
     *
     * @param raw 原始文本，可为 {@code null}
     * @return 单行文本，保证非 {@code null}（入参为 {@code null} 时返回空串）
     */
    public static String singleLine(String raw) {
        if (raw == null) {
            return "";
        }
        String stripped = ControlChars.strip(raw).replace('\n', ' ');
        return stripped.length() <= MAX_CHARS ? stripped : stripped.substring(0, MAX_CHARS) + "…";
    }
}
