package zcd.jellyfish.core.prompt;

import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.llm.LlmMessage;
import zcd.jellyfish.infra.tooloutput.ToolOutputEnvelope;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 工具结果老化：把「发往模型的请求」里较早的大结果替换成一行 stub。
 * <p>
 * <b>只改本次请求</b>：返回的是新的消息列表，{@code Session} 里存的历史一条不动——与
 * {@link ContextWindow} 同一口径。屏幕投影、持久化、{@code /resume} 看到的仍是完整结果，
 * 只有发给模型的那条链路变短了。
 * <p>
 * <b>为什么需要它</b>：机械裁剪（{@link ContextWindow}）只在总量超预算时才动手，而工具结果
 * 的特点是「单条就很大、还很早」——几十轮前读过的一个大文件，会一直占着上下文，直到某一次
 * 装不下被整组丢掉，那时连「曾经读过它、内容在哪」都不知道了。提前把旧大结果换成带路径的 stub，
 * 换回来的是「最近几轮仍然完整 + 更早的仍可回查」。
 * <p>
 * <b>只动截断信封，不动普通结果</b>：只有超限落盘过的结果才有信封。小结果本来就不占地方，
 * 把它们也换成 stub 只会让模型无端失忆。
 * <p>
 * 无状态，可安全复用。
 *
 * @author zcd
 */
@Singleton
public class ToolResultAger {

    /** 运行时配置门面：保留条数现读，热更新后下一轮生效。 */
    private final RuntimeConfig runtimeConfig;

    /**
     * 构造老化器。
     *
     * @param runtimeConfig 运行时配置门面，不可为 {@code null}
     */
    @Inject
    public ToolResultAger(RuntimeConfig runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
    }

    /**
     * 把较早的工具结果信封替换成 stub；更近的保留完整，非信封结果一律不动。
     *
     * @param messages 待发往模型的消息列表，可为 {@code null}
     * @return 老化后的消息列表；没有可老化的内容时返回原列表
     */
    public List<LlmMessage> age(List<LlmMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages == null ? Collections.<LlmMessage>emptyList() : messages;
        }
        int keepRecent = runtimeConfig.getReactSettings().getToolOutput().getKeepRecentMessages();
        if (keepRecent <= 0) {
            return messages;
        }
        int boundary = messages.size() - keepRecent;
        List<LlmMessage> aged = null;
        for (int index = 0; index < boundary; index++) {
            LlmMessage message = messages.get(index);
            if (!LlmMessage.ROLE_TOOL.equals(message.getRole())) {
                continue;
            }
            ToolOutputEnvelope envelope = ToolOutputEnvelope.parse(message.getContent());
            if (envelope == null) {
                continue;
            }
            if (aged == null) {
                aged = new ArrayList<LlmMessage>(messages);
            }
            aged.set(index, new LlmMessage(message.getRole(), envelope.stub(), message.getToolCallId(),
                    message.getName(), null));
        }
        return aged == null ? messages : Collections.unmodifiableList(aged);
    }
}
