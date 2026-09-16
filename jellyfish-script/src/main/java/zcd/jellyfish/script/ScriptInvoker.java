package zcd.jellyfish.script;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一次脚本调用的通道：把「扩展点类型名 + 请求载荷」送出去，拿回结果载荷。
 * <p>
 * <b>为什么是接口而不是直接把调用方传进来</b>：{@code codec} 只该关心「请求怎么变成 JSON、
 * 结果 JSON 怎么变回值对象」，不该知道脚本标识、进程、超时与熔断这些运行时概念。
 * 让调用方在注册时把这个窄接口闭包进 {@code codec}，编解码器就能保持纯粹的纯函数，
 * 而运行时概念的演化也不会波及它们。
 * <p>
 * 实现方<b>不返回 {@code null}</b> 是一个软约定而非硬保证：{@link ExtensionCodec} 的解码方法
 * 必须接受 {@code null}（协议层可能确实没有结果载荷），因此实现方返回 {@code null}
 * 一律按「空结果」处理。
 *
 * @author zcd
 */
@FunctionalInterface
public interface ScriptInvoker {

    /**
     * 调用一次脚本扩展点。
     *
     * @param typeName 扩展点类型名（{@code codec} 自己给出，例如 {@code tool}），不可为空白
     * @param request  请求载荷，可为 {@code null}（表示该扩展点的请求没有载荷）
     * @return 结果载荷；脚本没有结果载荷时为 {@code null}
     */
    JsonNode invoke(String typeName, JsonNode request);
}
