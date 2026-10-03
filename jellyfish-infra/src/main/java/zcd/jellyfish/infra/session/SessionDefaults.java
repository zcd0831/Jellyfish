package zcd.jellyfish.infra.session;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 本进程内「新建会话时使用的默认值」：agent / provider / model。
 * <p>
 * <b>它解决的是「首页上设置的那几项该落到哪」</b>：用户在还没有会话时敲
 * {@code /model openai/gpt-4o}，意图明确是「我接下来这次对话要用它」，而不是
 * 「请给我建一个空会话再站进去」。因此那一敲写在这里，{@link SessionManager#create} 建会话时
 * 再把它当作缺省值填进去——没有会话产生、用户留在首页。
 * <p>
 * <b>纯内存，绝不写回任何配置文件</b>：写配置文件是另一整层能力（写全局还是项目级？项目级覆盖时
 * 写全局等于无效；格式保真；与 {@code /reload} 的顺序）。而 {@code -cli --model x} 今天也是进程级的，
 * 两者语义一致，不制造第二套「默认」。代价是进程退出即失效——这与「用户没有明确要求永久修改」相符。
 * <p>
 * <b>不覆盖配置文件里的默认值，只盖在它上面</b>：某个字段为 {@code null} 表示「这一项继续跟随
 * 更下层的默认（{@code models.json} 的 {@code defaultProvider} / {@code defaultModel}、
 * 恒为内置的默认 agent）」。因此「设过模型但没设 agent」不会顺手把 agent 也钉死。
 * <p>
 * <b>不涉及持久化</b>：它没有任何 {@code SessionPersistRequest} 之类的副作用，改一次就只是改一次。
 * <p>
 * 线程安全：整体替换成新的不可变 {@link Values} 快照，因此并发下不会读到「一半新一半旧」的组合
 * （外壳的渲染线程与命令的执行线程确实可能同时读写）。
 *
 * @author zcd
 */
@Singleton
public final class SessionDefaults {

    /** 当前的一组默认值，整体替换。 */
    private final AtomicReference<Values> current =
            new AtomicReference<Values>(new Values(null, null, null));

    /**
     * 构造空的默认值集合。
     */
    @Inject
    public SessionDefaults() {
    }

    /**
     * 取当前默认值的快照。
     * <p>
     * 返回值不可变，因此调用方可以在多处之间比较、也可以在锁外使用。
     *
     * @return 当前默认值，保证非 {@code null}
     */
    public Values snapshot() {
        return current.get();
    }

    /**
     * 设置下次建会话要用哪个 agent。
     *
     * @param agentId agent 标识，可为 {@code null}（表示恢复为「跟随内置默认 agent」）
     */
    public void setAgentId(String agentId) {
        current.updateAndGet(values -> new Values(agentId, values.provider, values.model));
    }

    /**
     * 设置下次建会话要用哪个模型。
     *
     * @param provider provider 名，可为 {@code null}
     * @param model    模型名，可为 {@code null}
     */
    public void setModel(String provider, String model) {
        current.updateAndGet(values -> new Values(values.agentId, provider, model));
    }

    /**
     * 一组不可变的默认值；字段为 {@code null} 表示「这一项跟随更下层的默认」。
     * <p>
     * <b>为什么要一个值对象而不是三个字段</b>：{@code SessionManager.create} 需要一次性读齐三项，
     * 分开读可能在两次读之间被另一个线程改掉中间项，从而建出一个「agent 是新的、模型是旧的」会话。
     *
     * @author zcd
     */
    public static final class Values {

        /** 默认 agent 标识，可为 {@code null}。 */
        private final String agentId;

        /** 默认 provider，可为 {@code null}。 */
        private final String provider;

        /** 默认模型名，可为 {@code null}。 */
        private final String model;

        /**
         * 构造一组默认值。
         *
         * @param agentId  默认 agent 标识，可为 {@code null}
         * @param provider 默认 provider，可为 {@code null}
         * @param model    默认模型名，可为 {@code null}
         */
        private Values(String agentId, String provider, String model) {
            this.agentId = agentId;
            this.provider = provider;
            this.model = model;
        }

        /**
         * 获取默认 agent 标识。
         *
         * @return agent 标识，未设置时为 {@code null}
         */
        public String getAgentId() {
            return agentId;
        }

        /**
         * 获取默认 provider。
         *
         * @return provider 名，未设置时为 {@code null}
         */
        public String getProvider() {
            return provider;
        }

        /**
         * 获取默认模型名。
         *
         * @return 模型名，未设置时为 {@code null}
         */
        public String getModel() {
            return model;
        }

        /**
         * 判断三项是否都没设置。
         *
         * @return 全部为 {@code null} 返回 {@code true}
         */
        public boolean isEmpty() {
            return agentId == null && provider == null && model == null;
        }
    }
}
