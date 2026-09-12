package zcd.jellyfish.infra.llm;

import zcd.jellyfish.infra.config.Provider;

/**
 * 由一个 {@link Provider} 配置创建对应 {@link LlmClient} 的工厂函数。
 * <p>
 * 各实现通过 Dagger2 以 {@code Map<String, LlmClientCreator>} 的形式注册到
 * {@link LlmClientFactory}，由工厂按 provider type 路由。
 *
 * @author zcd
 */
@FunctionalInterface
public interface LlmClientCreator {

    /**
     * 创建客户端实例。
     *
     * @param provider provider 配置
     * @return 客户端实例，不应返回 {@code null}
     */
    LlmClient create(Provider provider);
}
