package zcd.jellyfish.api.extension;

/**
 * 提示词贡献请求：内核在组装某次请求的 system prompt 前构造，询问「这个会话还有没有要追加的上下文」。
 * <p>
 * 这是<b>类型级</b>扩展点（{@link #getRouteKey()} 恒为 {@code null}）：同一会话允许多个插件各贡献一段，
 * 因此用 {@code PluginContext.contribute} 注册，内核按 {@code order} 升序依次询问并拼接。
 * <p>
 * <b>为什么需要这个扩展点</b>：插件拿不到会话，也无法往消息列表里插东西（那会被当成对话历史，
 * 每轮重复累积、回放与 token 统计都会失真）。可它手上的状态（待办、召回的记忆……）又必须让模型看见，
 * 于是只剩一处合法的注入点——system prompt。{@code sessionId} 就是插件找回自己那份状态的钥匙。
 * <p>
 * 不经此处的插件不受影响：没有处理器时内核不下发任何额外上下文。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class PromptContributionRequest extends ExtensionRequest<PromptContribution> {

    /**
     * 构造提示词贡献请求。
     *
     * @param sessionId 会话标识，可为 {@code null}
     */
    public PromptContributionRequest(String sessionId) {
        super(PromptContribution.class, sessionId);
    }

    @Override
    public String getRouteKey() {
        return null;
    }
}
