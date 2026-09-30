package zcd.jellyfish.core.input;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.InputTransformRequest;
import zcd.jellyfish.api.extension.InputTransformResult;
import zcd.jellyfish.infra.extension.ExtensionRegistry;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import java.util.Objects;

/**
 * 输入改写服务：把「用户敲下的这一段文本」先过一遍插件，再由外壳决定它是对话还是别的东西。
 * <p>
 * <b>与 {@link InputDirectives} 的分工</b>：那个管的是<b>标记式</b>语法（{@code !} 执行命令、
 * {@code @} 引用文件），标记本身就是路由键，语义是「这一行要干点什么」；本类管的是
 * <b>任意文本的改写与短路</b>，不认标记，语义是「要不要换一段文本 / 要不要整个接过去」。
 * 合成一个类会让「这条输入谁说了算」变成一个需要判别的字段，而这正是最难排查的那类问题。
 * <p>
 * <b>为什么编排在本类而注册表不参与</b>：链式传递与合并规则是调用点的事
 * （见 {@code constraints/extensions.md}），注册表只提供「有序查找」与「执行单个处理器」。
 * <p>
 * <b>顺序是硬的，而且由外壳保证</b>：本类只负责跑链，<b>排在命令判定之后、指令解析之前、
 * 建会话之前</b>这三条由三个外壳的调用点各自落实（理由见 {@link InputTransformRequest}）。
 * 因此本类刻意不提供「一步到位把输入变成回合」的入口——那会把顺序藏起来，而顺序正是本扩展点的全部难点。
 * <p>
 * <b>线程</b>：在调用线程上同步跑完。TUI 路径上那是<b>渲染线程</b>，因此处理器必须纯计算、不阻塞；
 * 内核不设超时，这是同步侧既有语义。
 *
 * @author zcd
 */
@Singleton
public class InputTransforms {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(InputTransforms.class);

    /** 同步扩展点策略。 */
    private final ExtensionRegistry extensions;

    /**
     * 构造输入改写服务。
     *
     * @param extensions 同步扩展点策略，不可为 {@code null}
     */
    @Inject
    public InputTransforms(ExtensionRegistry extensions) {
        this.extensions = Objects.requireNonNull(extensions, "extensions must not be null");
    }

    /**
     * 跑一遍输入改写链。
     * <p>
     * <b>链式语义</b>（写在调用点的 {@code for} 循环里，注册表不参与）：每个处理器收到
     * <b>上一个处理器产出的</b>文本（首个收到原文）；{@code continueAsIs} 保持当前值继续；
     * {@code replace} 替换当前值继续；{@code handled} <b>立即短路</b>——后面的处理器不再被调用，
     * 因为「这次输入不进对话了」之后就没有文本可改。
     * <p>
     * <b>失败语义是「保持当前文本」</b>：同步派发没有护栏，异常处置是本方法的责任；
     * 一个坏插件不该让用户敲完回车之后什么都发不出去。
     * <p>
     * <b>无插件时返回 {@link InputTransformResult#continueAsIs()} 且不构造任何请求对象</b>，
     * 外壳因此原样沿用自己手里那份文本，行为与引入本扩展点之前逐字节一致。
     *
     * @param sessionId 会话标识，可为 {@code null}（首页无会话）
     * @param text      用户输入原文，不可为 {@code null}
     * @param source    输入来自哪种外壳，不可为 {@code null}
     * @return 最终结果，保证非 {@code null}
     */
    public InputTransformResult transform(String sessionId, String text, InputTransformRequest.Source source) {
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(source, "source must not be null");
        List<ExtensionHandler<InputTransformRequest, InputTransformResult>> handlers =
                extensions.handlers(InputTransformRequest.class, null);
        if (handlers.isEmpty()) {
            return InputTransformResult.continueAsIs();
        }
        String current = text;
        boolean replaced = false;
        for (ExtensionHandler<InputTransformRequest, InputTransformResult> handler : handlers) {
            InputTransformResult result;
            try {
                result = extensions.invoke(handler,
                        new InputTransformRequest(sessionId, current, source, sessionId != null));
            } catch (RuntimeException e) {
                LOG.warn("输入改写处理器抛错，按不改处理: source={}", source, e);
                continue;
            }
            if (result == null) {
                continue;
            }
            if (result.isHandled()) {
                return result;
            }
            if (result.hasText()) {
                current = result.getText();
                replaced = true;
            }
        }
        return replaced ? InputTransformResult.replace(current) : InputTransformResult.continueAsIs();
    }
}
