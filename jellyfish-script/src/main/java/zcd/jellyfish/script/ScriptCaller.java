package zcd.jellyfish.script;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 脚本调用入口：把一个扩展点调用送到脚本进程并取回结果。
 * <p>
 * <b>它是「注册」与「执行」之间的那道缝</b>，也是本方案里唯一允许出现「尚未接通」状态的接缝：
 * 注册（清单 → 转发处理器）在插件 {@code start()} 期完成，而进程、协议与 worker 是后续阶段的事。
 * 把执行收在一个接口后面，注册侧就能先落地并被完整验证，进程侧只需换一个实现，
 * 而不必重写注册逻辑。
 * <p>
 * 实现方对失败的处理<b>与 Java 插件一致</b>：抛 {@code JellyfishException}，
 * 由内核调用点决定怎么处置（工具调用会转成「工具执行失败：…」回灌、提示词贡献会跳过并告警、
 * 权限拦截会按「无异议」处理）。因此实现方不要在这里吞异常，也不要返回伪造的「成功但空」的结果——
 * 后者会让失败看起来像「脚本说没有内容」。
 *
 * @author zcd
 */
@FunctionalInterface
public interface ScriptCaller {

    /**
     * 调用一次脚本扩展点。
     *
     * @param plugin   目标脚本，不可为 {@code null}
     * @param typeName 扩展点类型名（由 codec 给出），不可为空白
     * @param request  请求载荷，可为 {@code null}（表示该扩展点的请求没有载荷）
     * @return 结果载荷；脚本没有结果载荷时为 {@code null}
     * @throws zcd.jellyfish.api.JellyfishException 调用失败时抛出
     */
    JsonNode call(ScriptPlugin plugin, String typeName, JsonNode request);
}
