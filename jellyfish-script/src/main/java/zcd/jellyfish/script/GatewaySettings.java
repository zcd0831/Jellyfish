package zcd.jellyfish.script;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.JellyfishException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 下发给脚本网关的设置：超时、空闲自毁、严格校验、事件收窄。
 * <p>
 * <b>为什么这些值由 Java 侧算好再下发，而不是让网关自己读配置</b>：网关是每种语言各写一份的
 * 薄控制进程，让它解析配置就等于让每种语言各实现一遍「缺省值、类型校验、单位换算」——
 * 三份实现里必然有两份会漂移。把配置收敛在 Java 侧，网关只接受一份已归一的数据，
 * 于是「新增一种语言 = 换个解释器 + 换份网关资源」这句话才站得住。
 * <p>
 * <b>哪些不在这里</b>：熔断参数（{@code failuresToOpen} 等）只在 Java 侧使用，网关不需要知道；
 * 事件白名单则相反，必须下发——防循环、类型校验都在 Java 侧做，但「推给谁」要由网关按脚本声明路由。
 * PID 文件路径则必须下发：它是文件系统上的一个位置，由 Java 侧统一算好（见 {@link ScriptPidFiles}）。
 * <p>
 * <b>超时是双重意义的</b>：网关用它判断 worker 是否卡死（自己动手杀），
 * Java 侧用它判断网关是否失联（{@code ScriptGateway} 的等待上限）。
 * 两者刻意取同一个值：如果 Java 侧比网关先超时，网关就来不及杀掉挂死的 worker，
 * 于是下一次调用会撞上同一个卡死的 worker，表现为「连续超时」。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class GatewaySettings {

    /** 默认单次调用超时秒数。 */
    public static final int DEFAULT_INVOKE_TIMEOUT_SECONDS = 30;

    /** 默认 worker 空闲自毁秒数。 */
    public static final int DEFAULT_WORKER_IDLE_SECONDS = 300;

    /** 默认网关空闲自毁秒数。 */
    public static final int DEFAULT_GATEWAY_IDLE_SECONDS = 600;

    /** 默认是否启用清单与实现的严格校验。 */
    public static final boolean DEFAULT_MANIFEST_STRICT = true;

    /** 单次调用超时秒数；{@code 0} 表示不超时。 */
    private final int invokeTimeoutSeconds;

    /** worker 空闲自毁秒数；{@code 0} 表示不回收。 */
    private final int workerIdleSeconds;

    /** 网关空闲自毁秒数；{@code 0} 表示不回收。 */
    private final int gatewayIdleSeconds;

    /** 是否启用清单与实现的严格校验。 */
    private final boolean manifestStrict;

    /** 额外收窄的事件白名单（为空表示不额外收窄）。 */
    private final List<String> allowedEvents;

    /** 网关 PID 文件路径；{@code null} 表示不写 PID 文件。 */
    private final String pidFile;

    /**
     * 构造设置。
     *
     * @param invokeTimeoutSeconds 单次调用超时秒数
     * @param workerIdleSeconds    worker 空闲自毁秒数
     * @param gatewayIdleSeconds   网关空闲自毁秒数
     * @param manifestStrict       是否启用严格校验
     * @param allowedEvents        额外收窄的事件白名单
     * @param pidFile              网关 PID 文件路径，可为 {@code null}
     */
    private GatewaySettings(int invokeTimeoutSeconds, int workerIdleSeconds, int gatewayIdleSeconds,
                            boolean manifestStrict, List<String> allowedEvents, String pidFile) {
        this.invokeTimeoutSeconds = invokeTimeoutSeconds;
        this.workerIdleSeconds = workerIdleSeconds;
        this.gatewayIdleSeconds = gatewayIdleSeconds;
        this.manifestStrict = manifestStrict;
        this.allowedEvents = allowedEvents;
        this.pidFile = pidFile;
    }

    /**
     * 构造全部取默认值的设置。
     *
     * @return 默认设置
     */
    public static GatewaySettings defaults() {
        return builder().build();
    }

    /**
     * 构造设置构建器。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 获取单次调用超时秒数。
     *
     * @return 超时秒数；{@code 0} 表示不超时
     */
    public int invokeTimeoutSeconds() {
        return invokeTimeoutSeconds;
    }

    /**
     * 获取单次调用超时毫秒数。
     *
     * @return 超时毫秒数；{@code 0} 表示不超时
     */
    public long invokeTimeoutMillis() {
        return invokeTimeoutSeconds * 1000L;
    }

    /**
     * 获取 worker 空闲自毁秒数。
     *
     * @return 秒数；{@code 0} 表示不回收
     */
    public int workerIdleSeconds() {
        return workerIdleSeconds;
    }

    /**
     * 获取网关空闲自毁秒数。
     *
     * @return 秒数；{@code 0} 表示不回收
     */
    public int gatewayIdleSeconds() {
        return gatewayIdleSeconds;
    }

    /**
     * 判断是否启用清单与实现的严格校验。
     *
     * @return 启用返回 {@code true}
     */
    public boolean manifestStrict() {
        return manifestStrict;
    }

    /**
     * 获取额外收窄的事件白名单。
     *
     * @return 不可变列表，可能为空
     */
    public List<String> allowedEvents() {
        return allowedEvents;
    }

    /**
     * 获取网关 PID 文件路径。
     *
     * @return 路径；{@code null} 表示不写 PID 文件
     */
    public String pidFile() {
        return pidFile;
    }

    /**
     * 编码成 {@code initialize} 的 {@code settings} 载荷。
     *
     * @return JSON 对象节点
     */
    public JsonNode toJson() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("invokeTimeoutSeconds", Integer.valueOf(invokeTimeoutSeconds));
        values.put("workerIdleSeconds", Integer.valueOf(workerIdleSeconds));
        values.put("gatewayIdleSeconds", Integer.valueOf(gatewayIdleSeconds));
        values.put("manifestStrict", Boolean.valueOf(manifestStrict));
        values.put("allowedEvents", allowedEvents);
        if (pidFile != null) {
            // 不写 null：网关用「键不存在」判断「不写 PID 文件」，与其它载荷的约定一致
            values.put("pidFile", pidFile);
        }
        return ScriptJson.treeOf(values);
    }

    @Override
    public String toString() {
        return "GatewaySettings{invokeTimeout=" + invokeTimeoutSeconds
                + "s, workerIdle=" + workerIdleSeconds + "s, gatewayIdle=" + gatewayIdleSeconds
                + "s, manifestStrict=" + manifestStrict + ", allowedEvents=" + allowedEvents
                + ", pidFile=" + pidFile + '}';
    }

    /**
     * 设置构建器。
     * <p>
     * 用构建器而不是全参构造器：参数里有三个「秒数」和一个布尔，位置写错一个在类型上完全看不出来，
     * 而它们的错误后果是「空闲回收过早」或「超时太短」这类需要跑很久才暴露的问题。
     * <p>
     * 非线程安全，仅供装配期单线程使用。
     *
     * @author zcd
     */
    public static final class Builder {

        /** 单次调用超时秒数。 */
        private int invokeTimeoutSeconds = DEFAULT_INVOKE_TIMEOUT_SECONDS;

        /** worker 空闲自毁秒数。 */
        private int workerIdleSeconds = DEFAULT_WORKER_IDLE_SECONDS;

        /** 网关空闲自毁秒数。 */
        private int gatewayIdleSeconds = DEFAULT_GATEWAY_IDLE_SECONDS;

        /** 是否启用严格校验。 */
        private boolean manifestStrict = DEFAULT_MANIFEST_STRICT;

        /** 额外收窄的事件白名单。 */
        private final List<String> allowedEvents = new ArrayList<String>();

        /** 网关 PID 文件路径。 */
        private String pidFile;

        /**
         * 常量类风格的私有构造器，仅允许 {@link GatewaySettings#builder()} 调用。
         */
        private Builder() {
        }

        /**
         * 设置单次调用超时秒数。
         *
         * @param seconds 秒数，不可为负
         * @return 本构建器
         * @throws JellyfishException 为负时抛出
         */
        public Builder invokeTimeoutSeconds(int seconds) {
            this.invokeTimeoutSeconds = requireNonNegative(seconds, "invokeTimeoutSeconds");
            return this;
        }

        /**
         * 设置 worker 空闲自毁秒数。
         *
         * @param seconds 秒数，不可为负
         * @return 本构建器
         * @throws JellyfishException 为负时抛出
         */
        public Builder workerIdleSeconds(int seconds) {
            this.workerIdleSeconds = requireNonNegative(seconds, "workerIdleSeconds");
            return this;
        }

        /**
         * 设置网关空闲自毁秒数。
         *
         * @param seconds 秒数，不可为负
         * @return 本构建器
         * @throws JellyfishException 为负时抛出
         */
        public Builder gatewayIdleSeconds(int seconds) {
            this.gatewayIdleSeconds = requireNonNegative(seconds, "gatewayIdleSeconds");
            return this;
        }

        /**
         * 设置是否启用严格校验。
         *
         * @param strict 启用传 {@code true}
         * @return 本构建器
         */
        public Builder manifestStrict(boolean strict) {
            this.manifestStrict = strict;
            return this;
        }

        /**
         * 追加额外收窄的事件白名单。
         *
         * @param eventNames 事件名，可为 {@code null}
         * @return 本构建器
         */
        public Builder allowedEvents(List<String> eventNames) {
            if (eventNames != null) {
                allowedEvents.addAll(eventNames);
            }
            return this;
        }

        /**
         * 设置网关 PID 文件路径。
         * <p>
         * 传 {@code null} 表示不写 PID 文件（测试与「不需要排查线索」的嵌入场景）；
         * 传空白串<b>报错</b>而不是当成「不写」：那是一个写坏了的路径，静默关掉这个能力
         * 会让人以为它启用了，直到真的需要线索时才发现什么都没有。
         *
         * @param path 绝对路径，可为 {@code null}
         * @return 本构建器
         * @throws JellyfishException 路径为空白时抛出
         */
        public Builder pidFile(String path) {
            if (path == null) {
                this.pidFile = null;
                return this;
            }
            if (path.trim().isEmpty()) {
                throw new JellyfishException("网关设置 pidFile 不得为空白（不写 PID 文件请显式传 null）");
            }
            this.pidFile = path.trim();
            return this;
        }

        /**
         * 构造设置。
         *
         * @return 设置
         */
        public GatewaySettings build() {
            return new GatewaySettings(invokeTimeoutSeconds, workerIdleSeconds, gatewayIdleSeconds,
                    manifestStrict, Collections.unmodifiableList(new ArrayList<String>(allowedEvents)),
                    pidFile);
        }

        /**
         * 校验参数非负。
         * <p>
         * <b>只拒绝负数，不拒绝零</b>：零在三个参数上都有确定含义（不超时 / 不回收），
         * 而负数只可能是配置写错。把负数当成零处理会让「写错的配置」静默生效。
         *
         * @param value 待校验值
         * @param name  字段名，用于报错
         * @return 原值
         * @throws JellyfishException 为负时抛出
         */
        private static int requireNonNegative(int value, String name) {
            if (value < 0) {
                throw new JellyfishException("网关设置 " + name + " 不得为负: " + value);
            }
            return value;
        }
    }
}
