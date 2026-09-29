package zcd.jellyfish.api.extension;

import java.util.Map;

/**
 * 工具结果元数据的<b>最小约定</b>：内核只认三个键，其余键归工具自己。
 * <p>
 * <b>为什么要有这层约定</b>：工具结果对模型是一段文本（首行写结论、正文写内容），而对界面与审计
 * 是数据。没有约定时，界面只能去「解析那一行文本」才能知道命令成没成——把展示绑死在文案格式上，
 * 改一个措辞就会让警告标记消失。有了约定之后，界面读字段、模型读文本，各自稳定。
 * <p>
 * <b>三个键分别回答三个不同的问题</b>：{@link #KEY_EXIT_CODE} 与 {@link #KEY_TERMINAL} 回答
 * 「它成没成」（判据 {@link #failed}），界面上表现为红色警示后缀；{@link #KEY_SUMMARY} 回答
 * 「刚才那一行轨迹到底是什么事」，界面上表现为工具名后面那句普通说明。
 * <p>
 * <b>为什么只有一个摘要键，而不是每个工具一套展示字段</b>：外壳对工具一无所知是这套架构的前提
 * （与「外壳不维护命令名单」同一条纪律）。若让界面去认某个工具自定的键，外壳就得认识那个工具——
 * 每多支持一个工具就多一处特例。约定一个「工具自己拼好的单行说明」之后，外壳只做「有就接在工具名后面」
 * 这一个判断，如何措辞、带哪些数字全归工具自己决定。
 * <p>
 * <b>为什么仍然只有三个键</b>：内核能解释的语义越少越好。上面三个键加起来回答「成没成」与
 * 「这是什么」，足够通用；再多就是替工具设计自己的 schema 了。工具可以带任意其它键，内核
 * <b>只透传、不解释</b>（{@link ToolCallResult#getMetadata()} → 外壳），消费方自己按需读取。
 * <p>
 * <b>未知键为什么不丢弃</b>：内核不认识它不等于没人认识。丢掉等于替所有消费方做了主，
 * 而透传的成本是一个引用。
 *
 * @author zcd
 */
public final class ToolMetadata {

    /**
     * 退出码键，值为整数。
     * <p>
     * 只在「命令真的自己跑完」时有意义；被外部终止（超时、取消）时不应填它——
     * 那一档的退出码只反映我们发的信号，报出来会被读成「命令自己出了问题」。
     */
    public static final String KEY_EXIT_CODE = "exitCode";

    /**
     * 终止原因键，值为字符串。
     * <p>
     * 约定「缺省 = 正常跑完」：{@code COMPLETED} 是各实现里表示正常完成的取值，
     * 因此工具要么不填这个键，要么填一个<b>不等于</b> {@code COMPLETED} 的取值来表示
     * 「它是被终止的」。这样内核不需要认识每种工具的全部终止枚举。
     */
    public static final String KEY_TERMINAL = "terminal";

    /** 正常完成的取值。 */
    public static final String TERMINAL_COMPLETED = "COMPLETED";

    /**
     * 单行摘要键，值为字符串。
     * <p>
     * 语义是「工具想让人在轨迹行上看到的那一句话」，例如 {@code 子代理 scout · 3 轮}。外壳把它
     * 接在工具名后面原样显示，<b>不做任何解释、也不解析它的内容</b>。
     * <p>
     * <b>为什么由工具自己拼成一句话，而不是给界面若干字段让它拼</b>：怎么措辞、该带哪几个数量，
     * 是工具自己才知道的事；拆成字段就意味着外壳得认识每一个字段的含义（见类注释）。
     * <p>
     * <b>与首行文案的分工</b>：首行文案是给<b>模型</b>读的（它要据此判断这次调用成没成、要不要换个参数重来），
     * 而本键是给<b>人</b>读的（它要在屏幕上扫一眼就知道刚才发生了什么）。两者受众不同，
     * 因此措辞可以各按各的需要写，不必强求一致。
     * <p>
     * <b>它是展示用的事实而不是判据</b>：任何<b>逻辑</b>分支都不该读它——要判断成没成请用
     * {@link #failed}，要判断工具语义请读工具自定的结构化键。
     * <p>
     * 缺省（缺键）= 没有额外说明，外壳就只显示工具名。
     */
    public static final String KEY_SUMMARY = "summary";

    /**
     * 工具类，禁止实例化。
     */
    private ToolMetadata() {
    }

    /**
     * 判断一条元数据是否<b>值得警示</b>：命令没能正常跑完。
     * <p>
     * 两种情形：退出码非零，或终止原因不是正常完成。它是界面渲染警告标记的唯一判据——
     * 放在这里而不是各界面各写一遍，否则「超时算不算失败」迟早会有两种答案。
     * <p>
     * <b>与「工具调用失败」不是一回事</b>：工具抛异常那条路由 {@code success=false} 承载，
     * 而本方法管的是「工具成功地报告了一个不成功的命令」——{@code grep} 没找到、测试没通过
     * 都属于这一类，且必须在界面上一眼看得出。
     * <p>
     * 宽容处理坏数据：值不是数字、不是字符串等情形一律当作「无此信息」而不是抛异常。
     * 元数据是工具写的旁路信息，它写坏了不该炸掉一次渲染。
     *
     * @param metadata 元数据，可为 {@code null}
     * @return 值得警示返回 {@code true}
     */
    public static boolean failed(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }
        Object exitCode = metadata.get(KEY_EXIT_CODE);
        if (exitCode instanceof Number && ((Number) exitCode).intValue() != 0) {
            return true;
        }
        Object terminal = metadata.get(KEY_TERMINAL);
        if (terminal instanceof String) {
            String value = ((String) terminal).trim();
            return !value.isEmpty() && !TERMINAL_COMPLETED.equals(value);
        }
        return false;
    }

    /**
     * 取一条元数据里的单行摘要。
     * <p>
     * 与 {@link #failed} 同样是「宽容处理坏数据」：值不是字符串、是空白串、或压根没有这个键，
     * 一律返回空串（表示「没有额外说明」）而不是抛异常——元数据是工具写的旁路信息，
     * 它写坏了不该炸掉一次渲染。
     * <p>
     * <b>不在这里截断长度</b>：摘要多长由工具自己决定，而超出一行的部分由外壳的换行逻辑处理。
     * 在这里设一个长度上限，等于让「摘要能写多长」变成两个地方各有一份答案。
     *
     * @param metadata 元数据，可为 {@code null}
     * @return 摘要文本；无此信息时返回空串，保证非 {@code null}
     */
    public static String summaryOf(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "";
        }
        Object summary = metadata.get(KEY_SUMMARY);
        if (!(summary instanceof String)) {
            return "";
        }
        String text = ((String) summary).trim();
        return text.isEmpty() ? "" : text;
    }
}
