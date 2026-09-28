package zcd.jellyfish.api.extension;

import java.util.Map;

/**
 * 工具结果元数据的<b>最小约定</b>：内核只认两个键，其余键归工具自己。
 * <p>
 * <b>为什么要有这层约定</b>：工具结果对模型是一段文本（首行写结论、正文写内容），而对界面与审计
 * 是数据。没有约定时，界面只能去「解析那一行文本」才能知道命令成没成——把展示绑死在文案格式上，
 * 改一个措辞就会让警告标记消失。约定两个键之后，界面读字段、模型读文本，各自稳定。
 * <p>
 * <b>为什么只有两个键</b>：内核能解释的语义越少越好。`exitCode` 与 `terminal` 是「进程类工具」
 * 通用的事实，足够回答「它成没成」；再多就是替工具设计自己的 schema 了。工具可以带任意其它键，
 * 内核<b>只透传、不解释</b>（{@link ToolCallResult#getMetadata()} → 外壳），消费方自己按需读取。
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
}
