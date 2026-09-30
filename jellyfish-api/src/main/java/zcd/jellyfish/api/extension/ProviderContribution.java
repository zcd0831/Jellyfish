package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.llm.LlmTransport;

/**
 * 插件交回的 provider 类型贡献：这个类型由谁实现、拿什么去说话。
 * <p>
 * <b>两种结果</b>：{@link #unsupported()} 表示「这个类型不归我管」，{@link #of} 表示「归我管，
 * 用这个传输」。之所以允许「明确地不管」，是因为内核在有多个插件时需要一个能区分的答复——
 * 沉默（不注册）与「我在但这个类型不是我的」在诊断上是两件事。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ProviderContribution {

    /** 是否接管该类型。 */
    private final boolean supported;

    /** 展示名，用于诊断输出（{@code /model} 列表、启动日志）。 */
    private final String displayName;

    /** 传输实现，{@link #unsupported()} 时为 {@code null}。 */
    private final LlmTransport transport;

    /**
     * 构造贡献。
     *
     * @param supported   是否接管该类型
     * @param displayName 展示名，可为 {@code null}
     * @param transport   传输实现，不接管时可为 {@code null}
     */
    private ProviderContribution(boolean supported, String displayName,
                                 LlmTransport transport) {
        this.supported = supported;
        this.displayName = displayName;
        this.transport = transport;
    }

    /**
     * 构造「这个类型不归我管」的答复。
     *
     * @return 贡献
     */
    public static ProviderContribution unsupported() {
        return new ProviderContribution(false, null, null);
    }

    /**
     * 构造「这个类型归我管」的答复。
     * <p>
     * <b>这里不能声明静态模型列表</b>：模型属于 provider（一个传输实现可以服务同一类型下的多个
     * provider），而动态目录走 {@link ModelCatalogRequest}。两处都能声明模型，会引出「谁的答案算数」
     * 这条没人会读、也没人会记得的优先级规则。
     *
     * @param displayName 展示名，不可为空白
     * @param transport   传输实现，不可为 {@code null}
     * @return 贡献
     * @throws JellyfishException 展示名为空白或传输为 {@code null} 时抛出
     */
    public static ProviderContribution of(String displayName,
                                          LlmTransport transport) {
        if (displayName == null || displayName.trim().isEmpty()) {
            throw new JellyfishException("provider contribution displayName must not be blank");
        }
        if (transport == null) {
            throw new JellyfishException("provider contribution transport must not be null");
        }
        return new ProviderContribution(true, displayName, transport);
    }

    /**
     * 判断是否接管该类型。
     *
     * @return 接管时返回 {@code true}
     */
    public boolean isSupported() {
        return supported;
    }

    /**
     * 获取展示名。
     *
     * @return 展示名，不接管时为 {@code null}
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 获取传输实现。
     *
     * @return 传输实现，不接管时为 {@code null}
     */
    public LlmTransport getTransport() {
        return transport;
    }

    @Override
    public String toString() {
        return "ProviderContribution{supported=" + supported + ", displayName=" + displayName + '}';
    }
}
