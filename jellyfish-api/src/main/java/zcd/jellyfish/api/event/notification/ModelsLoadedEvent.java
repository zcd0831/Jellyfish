package zcd.jellyfish.api.event.notification;

import zcd.jellyfish.api.event.AbstractJellyfishEvent;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * provider / model 索引重建完成事件：{@code ModelManager} 每次刷新索引后广播。
 * <p>
 * 与 {@link AgentsLoadedEvent} 对称：表达「索引已重建」而非「配置发生了变更」，
 * 内容可以为空（「一个 provider 都没配」必须可见）。
 * <p>
 * 经 {@code EventChannel} 异步派发，属 best-effort，可丢弃。
 *
 * @author zcd
 */
public final class ModelsLoadedEvent extends AbstractJellyfishEvent {

    /** 配置声明的默认 provider 名，未配置时为 {@code null}。 */
    private final String defaultProvider;

    /** 配置声明的默认 model 名，未配置时为 {@code null}。 */
    private final String defaultModel;

    /** 本次索引到的 provider 名集合。 */
    private final Set<String> providerNames;

    /**
     * 构造进程级事件。
     *
     * @param defaultProvider 默认 provider 名，可为 {@code null}
     * @param defaultModel    默认 model 名，可为 {@code null}
     * @param providerNames   本次索引到的 provider 名集合，可为 {@code null}
     */
    public ModelsLoadedEvent(String defaultProvider, String defaultModel, Set<String> providerNames) {
        super(null);
        this.defaultProvider = defaultProvider;
        this.defaultModel = defaultModel;
        this.providerNames = providerNames == null
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(providerNames));
    }

    /**
     * 获取默认 provider 名。
     *
     * @return 默认 provider 名，未配置时为 {@code null}
     */
    public String getDefaultProvider() {
        return defaultProvider;
    }

    /**
     * 获取默认 model 名。
     *
     * @return 默认 model 名，未配置时为 {@code null}
     */
    public String getDefaultModel() {
        return defaultModel;
    }

    /**
     * 获取本次索引到的 provider 名集合。
     *
     * @return 不可修改集合，可能为空但不会为 {@code null}
     */
    public Set<String> getProviderNames() {
        return providerNames;
    }
}
