package zcd.jellyfish.script.codec;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ExtensionRequest;
import zcd.jellyfish.script.ScriptInvoker;

/**
 * 扩展点编解码器：把一个 Java 扩展点翻译成脚本侧的 JSON 载荷，再把结果翻回来。
 * <p>
 * <b>它是「脚本与 Java 插件同权」这句话的落点</b>：每个扩展点在 Java 侧由一个请求类型与一个结果类型
 * 定义，在协议侧由一对 JSON 形状定义，本接口就是这两者之间唯一的对应关系。
 * 因此新增一个扩展点 = 新增一个 codec，不动协议、不动进程模型。
 * <p>
 * <b>两个方法都是纯函数</b>：不碰进程、不碰注册表、不打日志，因此可以完全离线单测——
 * 这是「11 个扩展点全部打通」能被验证的前提。
 * <p>
 * {@link #handlerTo(ScriptInvoker)} 把编解码与调用拼成内核认得的
 * {@link ExtensionHandler}，并且<b>不引入任何 unchecked 转换</b>：类型参数 {@code C}、{@code R}
 * 在这里是真实可见的，注册表拿到的就是一个货真价实的
 * {@code ExtensionHandler<C, R>}。
 *
 * @param <C> 请求类型
 * @param <R> 结果类型
 * @author zcd
 */
public interface ExtensionCodec<C extends ExtensionRequest<R>, R> {

    /**
     * 获取协议里的扩展点类型名。
     * <p>
     * 该名字同时出现在三处，必须一致：脚本清单 {@code manifest.json} 的 {@code contributions}
     * 取值、{@code invoke} 请求的 {@code params.type}、以及本接口的实现类。
     * 前两者的校验都由 {@link ExtensionCodecs} 承担，因此不存在第三份真源。
     *
     * @return 类型名，保证非空白
     */
    String typeName();

    /**
     * 获取该扩展点的请求类型。
     * <p>
     * 注册用：内核按「类型 + 路由键」找处理器，本方法给出的就是那个类型。
     *
     * @return 请求类型
     */
    Class<C> requestType();

    /**
     * 判断该扩展点是类型级还是带路由键的。
     * <p>
     * 类型级（{@code true}）指「同一请求类型允许多个处理器」，落点是
     * {@code PluginContext.contribute}；带路由键的（{@code false}）指「同键唯一」，
     * 落点是 {@code PluginContext.handle}。这个差别不是 codec 自己选的，而是内核调用点
     * 决定的查询方式（{@code bindings(type, null)} 还是 {@code handler(type, routeKey)}），
     * 因此必须由 codec 如实声明。
     *
     * @return 类型级返回 {@code true}
     */
    boolean isTypeLevel();

    /**
     * 把请求编码成脚本侧载荷。
     *
     * @param request 请求对象，不可为 {@code null}
     * @return 载荷节点，保证非 {@code null}
     */
    JsonNode encodeRequest(C request);

    /**
     * 把脚本返回的结果解码成 Java 值对象。
     *
     * @param result   结果载荷；脚本返回空结果时为 {@code null}
     * @param routeKey 路由键（工具名 / 命令名），类型级扩展点为 {@code null}；供结果对象需要
     *                 回填名字的扩展点使用（如 {@code ToolCallResult}）
     * @return 结果值对象
     */
    R decodeResult(JsonNode result, String routeKey);

    /**
     * 把编解码器与调用通道拼成内核认得的处理器。
     * <p>
     * 顺序有意固定为「先编码、再调用、后解码」：编码失败就不该发起一次无意义的进程往返。
     *
     * @param invoker 调用通道，不可为 {@code null}
     * @return 处理器
     */
    default ExtensionHandler<C, R> handlerTo(ScriptInvoker invoker) {
        return request -> decodeResult(invoker.invoke(typeName(), encodeRequest(request)), request.getRouteKey());
    }
}
