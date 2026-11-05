package io.github.zcd0831.jellyfish.infrastructure.model;

import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.perception.port.PolicyRepository;
import io.github.zcd0831.jellyfish.application.shared.port.CancellationRegistry;
import io.github.zcd0831.jellyfish.application.shared.port.ProgressPort;
import io.github.zcd0831.jellyfish.application.shared.port.TokenCounter;

/**
 * ModelInvoker 的协作者。原先 8 个构造形参，加一个协作者就要动 8 个位置；
 * 收成一个对象后：生产只有 {@link #standard} 一条路，测试用 {@link #of} 换掉两个缝。
 * 规则出处：08-application §8.11（适配器依赖显式可见）。
 */
public final class InvokerWiring {

    final CatalogRepository catalog;
    final PolicyRepository policyRepository;
    final ChatTransport transport;
    final ProgressPort progress;
    final TokenCounter tokenCounter;
    final Sleeper sleeper;
    final CredentialLookup credentials;
    final CancellationRegistry cancellations;

    private InvokerWiring(CatalogRepository catalog, PolicyRepository policyRepository, ChatTransport transport,
                          ProgressPort progress, TokenCounter tokenCounter, Sleeper sleeper,
                          CredentialLookup credentials, CancellationRegistry cancellations) {
        this.catalog = catalog;
        this.policyRepository = policyRepository;
        this.transport = transport;
        this.progress = progress;
        this.tokenCounter = tokenCounter;
        this.sleeper = sleeper;
        this.credentials = credentials;
        this.cancellations = cancellations;
    }

    /** 生产：真等待（听令牌）、凭据读进程环境。 */
    public static InvokerWiring standard(CatalogRepository catalog, PolicyRepository policyRepository,
                                         ChatTransport transport, ProgressPort progress,
                                         TokenCounter tokenCounter, CancellationRegistry cancellations) {
        return new InvokerWiring(catalog, policyRepository, transport, progress, tokenCounter,
                new TokenAwareSleeper(), new EnvironmentCredentialLookup(), cancellations);
    }

    /** 没有会话归属的实例（测试、插件自建）：终止信号一律「永不取消」。 */
    public static InvokerWiring withoutSessions(CatalogRepository catalog, PolicyRepository policyRepository,
                                                ChatTransport transport, ProgressPort progress,
                                                TokenCounter tokenCounter) {
        return standard(catalog, policyRepository, transport, progress, tokenCounter, new CancellationRegistry() {
            @Override
            public io.github.zcd0831.jellyfish.application.shared.CancellationToken forSession(
                    io.github.zcd0831.jellyfish.domain.shared.SessionId sessionId) {
                return io.github.zcd0831.jellyfish.application.shared.CancellationToken.never();
            }
        });
    }

    static InvokerWiring of(CatalogRepository catalog, PolicyRepository policyRepository, ChatTransport transport,
                            ProgressPort progress, TokenCounter tokenCounter, Sleeper sleeper,
                            CredentialLookup credentials, CancellationRegistry cancellations) {
        return new InvokerWiring(catalog, policyRepository, transport, progress, tokenCounter, sleeper,
                credentials, cancellations);
    }

    /** 测试用，不关心终止：等价于换掉等待与凭据两个缝、终止一律「永不取消」。 */
    static InvokerWiring of(CatalogRepository catalog, PolicyRepository policyRepository, ChatTransport transport,
                            ProgressPort progress, TokenCounter tokenCounter, Sleeper sleeper,
                            CredentialLookup credentials) {
        return of(catalog, policyRepository, transport, progress, tokenCounter, sleeper, credentials,
                new CancellationRegistry() {
                    @Override
                    public io.github.zcd0831.jellyfish.application.shared.CancellationToken forSession(
                            io.github.zcd0831.jellyfish.domain.shared.SessionId sessionId) {
                        return io.github.zcd0831.jellyfish.application.shared.CancellationToken.never();
                    }
                });
    }

    /** 凭据只从进程环境读：真实密钥不落盘，也不进子进程（05-action §5.4）。 */
    private static final class EnvironmentCredentialLookup implements CredentialLookup {
        @Override
        public String lookup(String envName) {
            return System.getenv(envName);
        }
    }
}
