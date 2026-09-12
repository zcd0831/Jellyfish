package zcd.jellyfish.core;

import zcd.jellyfish.infra.config.RuntimeConfig;
import zcd.jellyfish.infra.event.JellyfishEventBus;
import zcd.jellyfish.infra.model.ModelManager;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Agent 运行时宿主与组装门面：外部入口（CLI / TUI / Server）只认它。
 * <p>
 * 目前只落地启动时序里与配置加载相关的一环，其余职责为占位：
 * <ul>
 *     <li>已在实现：启动事件总线 → 加载运行时配置 → 重建模型索引，保证启动期配置告警不丢失、
 *     {@code ModelManager} 在配置就绪后再建索引；</li>
 *     <li>待实现：注册核心组件订阅者、加载插件、驱动 ReAct 循环（思考 → 行动 → 观察）、关闭时优雅收敛。</li>
 * </ul>
 * 启动顺序有意固定为「先 {@code eventBus.start()} → 再 {@code runtimeConfig.refresh()} → 最后
 * {@code modelManager.refresh(false)}」：通知订阅者注册完成后再加载配置，配置层发出的
 * {@code ConfigWarningEvent} 才能被订阅到，且模型索引必然建立在已加载的配置之上。
 *
 * @author zcd
 */
@Singleton
public class AgentHarness {

    /** 运行时配置门面，负责配置的双源读取与合并。 */
    private final RuntimeConfig runtimeConfig;

    /** 事件总线门面，命令与通知的统一通道。 */
    private final JellyfishEventBus eventBus;

    /** 模型管理器，持有 provider / model 索引。 */
    private final ModelManager modelManager;

    /**
     * 构造运行时宿主。
     *
     * @param runtimeConfig 运行时配置门面
     * @param eventBus      事件总线
     * @param modelManager  模型管理器
     */
    @Inject
    public AgentHarness(RuntimeConfig runtimeConfig, JellyfishEventBus eventBus, ModelManager modelManager) {
        this.runtimeConfig = runtimeConfig;
        this.eventBus = eventBus;
        this.modelManager = modelManager;
    }

    /**
     * 启动应用：先启动事件总线，再加载运行时配置，最后重建模型索引。
     * <p>
     * 其余启动步骤（注册核心订阅者、加载插件）为占位，后续在此补充。
     */
    public void bootstrap() {
        eventBus.start();
        runtimeConfig.refresh();
        modelManager.refresh(false);
    }
}
