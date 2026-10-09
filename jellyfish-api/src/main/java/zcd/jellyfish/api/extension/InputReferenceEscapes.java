package zcd.jellyfish.api.extension;

/**
 * 行内引用的转义约定：路径里的空白用反斜杠转义（{@code my\ file.txt}）。
 * <p>
 * <b>为什么需要它</b>：片段是按空白切出来的（{@code @} 后面直到空白为止是一个片段），
 * 因此含空格的文件名不转义就会被切成两段——补全失效，模型拿到的路径也是错的
 * （{@code @my file.txt} 会被读成「引用了 my」）。
 * <p>
 * <b>规则只有这一处定义</b>：补全的那一侧（插件）用 {@link #escape(String)} 造插入文本、
 * 用 {@link #unescape(String)} 把用户接着敲的片段还原成路径；外壳那一侧用
 * {@link #isEscapedAt(String, int)} 判断片段该不该在某个空白处断开。三处若各写一份，
 * 迟早会出现「能补全但读不对」或「能读但补全断了」这类只有一半成立的现场。
 * <p>
 * <b>可逆</b>：{@code \} 转义成 {@code \\}、空白转义成 {@code \} + 该空白，
 * 因此 {@code unescape(escape(x)) == x} 恒成立；反过来，{@code \} 后面跟着不认识的字符时
 * <b>原样保留</b>（用户手敲的 {@code @C:\tmp} 不该被这层弄坏）。
 * <p>
 * 工具类，禁止实例化。
 *
 * @author zcd
 */
public final class InputReferenceEscapes {

    /** 转义字符。 */
    public static final char ESCAPE = '\\';

    /**
     * 工具类，禁止实例化。
     */
    private InputReferenceEscapes() {
    }

    /**
     * 把路径转义成可插入输入框、且能被重新切出来的文本。
     *
     * @param path 路径，可为 {@code null}
     * @return 转义后的文本；入参为 {@code null} 时返回 {@code null}
     */
    public static String escape(String path) {
        if (path == null) {
            return null;
        }
        StringBuilder escaped = new StringBuilder(path.length() + 8);
        for (int index = 0; index < path.length(); index++) {
            char current = path.charAt(index);
            if (current == ESCAPE) {
                escaped.append(ESCAPE).append(ESCAPE);
                continue;
            }
            if (Character.isWhitespace(current)) {
                escaped.append(ESCAPE);
            }
            escaped.append(current);
        }
        return escaped.toString();
    }

    /**
     * 把片段里的转义还原成路径。
     *
     * @param token 片段（{@code @} 之后的部分），可为 {@code null}
     * @return 还原后的文本；入参为 {@code null} 时返回 {@code null}
     */
    public static String unescape(String token) {
        if (token == null || token.indexOf(ESCAPE) < 0) {
            return token;
        }
        StringBuilder plain = new StringBuilder(token.length());
        for (int index = 0; index < token.length(); index++) {
            char current = token.charAt(index);
            if (current == ESCAPE && index + 1 < token.length()) {
                char next = token.charAt(index + 1);
                if (next == ESCAPE || Character.isWhitespace(next)) {
                    plain.append(next);
                    index++;
                    continue;
                }
            }
            plain.append(current);
        }
        return plain.toString();
    }

    /**
     * 判断某个位置上的字符是否被转义。
     * <p>
     * 判据是它<b>前面紧邻的反斜杠个数为奇数</b>：{@code \ } 里的空白是转义过的（片段不断），
     * 而 {@code \\ } 是「一个反斜杠 + 一个真边界」（片段在这里断）。
     *
     * @param text  输入全文，可为 {@code null}
     * @param index 待判断字符的下标
     * @return 被转义返回 {@code true}
     */
    public static boolean isEscapedAt(String text, int index) {
        if (text == null || index <= 0 || index > text.length()) {
            return false;
        }
        int escapes = 0;
        for (int position = index - 1; position >= 0 && text.charAt(position) == ESCAPE; position--) {
            escapes++;
        }
        return escapes % 2 == 1;
    }
}
