package zcd.jellyfish.di;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import zcd.jellyfish.core.compact.CompactionHealthIndicator;
import zcd.jellyfish.core.compact.ConversationCompactor;
import zcd.jellyfish.infra.event.EventChannel;
import zcd.jellyfish.infra.metrics.EventChannelHealthIndicator;
import zcd.jellyfish.infra.metrics.HealthCheck;
import zcd.jellyfish.infra.metrics.HealthIndicator;
import zcd.jellyfish.infra.metrics.ModelHealthIndicator;
import zcd.jellyfish.infra.metrics.PluginHealthIndicator;
import zcd.jellyfish.infra.model.ModelManager;
import zcd.jellyfish.infra.plugin.PF4JPluginManager;

/**
 * 可观测性相关依赖的 Dagger2 模块。
 * <p>
 * {@code MetricsRegistry} 与 {@code MetricsSubscriber} 自带 {@code @Inject} 构造器，由 Dagger 自行装配；
 * 本模块只负责 {@link HealthCheck}——因为检查项列表是<b>跨层</b>的（{@code infra} 的模型 / 插件 / 事件通道
 * 加上 {@code core} 的压缩器），只能在最外层显式拼装。
 * <p>
 * 这正是把 {@link HealthIndicator} 抽成接口的收益：{@code infra/metrics} 不必为了报告压缩状态
 * 去依赖 {@code core}，{@code core} 也不必知道还有哪些检查项。
 *
 * @author zcd
 */
@Module
public final class MetricsModule {

    private MetricsModule() {
    }

    /**
     * 提供健康检查汇总：按「模型 → 插件 → 事件通道 → 压缩」的顺序陈列检查项。
     *
     * @param modelManager   模型门面
     * @param pluginManager  插件运行时门面
     * @param eventChannel   事件通道
     * @param compactor      会话压缩器
     * @return 健康检查
     */
    @Provides
    @Singleton
    static HealthCheck provideHealthCheck(ModelManager modelManager, PF4JPluginManager pluginManager,
                                          EventChannel eventChannel, ConversationCompactor compactor) {
        List<HealthIndicator> indicators = new ArrayList<HealthIndicator>();
        indicators.add(new ModelHealthIndicator(modelManager));
        indicators.add(new PluginHealthIndicator(pluginManager));
        indicators.add(new EventChannelHealthIndicator(eventChannel));
        indicators.add(new CompactionHealthIndicator(compactor));
        return new HealthCheck(indicators);
    }
}
