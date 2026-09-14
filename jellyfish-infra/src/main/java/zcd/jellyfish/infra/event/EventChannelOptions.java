package zcd.jellyfish.infra.event;

import zcd.jellyfish.api.JellyfishException;

/**
 * 事件通道参数：线程池、启动期缓冲与关闭等待时间。
 * <p>
 * v1 使用代码默认值，后续再补 {@code event} 双源配置段。
 * <p>
 * <b>没有「嵌套深度」这类参数</b>：同步派发已改为调用点内联执行，护栏归调用方，
 * 通道只负责异步广播的队列与生命周期。
 *
 * @author zcd
 */
public final class EventChannelOptions {

    /** 默认核心线程数。 */
    private static final int DEFAULT_CORE_POOL_SIZE = 2;

    /** 默认最大线程数。 */
    private static final int DEFAULT_MAX_POOL_SIZE = 8;

    /** 默认空闲回收时间（秒）。 */
    private static final long DEFAULT_KEEP_ALIVE_SECONDS = 60L;

    /** 默认广播队列容量。 */
    private static final int DEFAULT_QUEUE_CAPACITY = 1024;

    /** 默认启动期缓冲容量。 */
    private static final int DEFAULT_PENDING_CAPACITY = 1024;

    /** 默认关闭等待时间（毫秒）。 */
    private static final long DEFAULT_SHUTDOWN_AWAIT_MILLIS = 5000L;

    /** 广播线程池核心线程数。 */
    private final int corePoolSize;

    /** 广播线程池最大线程数。 */
    private final int maxPoolSize;

    /** 线程空闲回收时间（秒）。 */
    private final long keepAliveSeconds;

    /** 广播队列容量，有界避免 OOM。 */
    private final int queueCapacity;

    /** 启动期缓冲容量。 */
    private final int pendingCapacity;

    /** 关闭时等待线程池排空的毫秒数。 */
    private final long shutdownAwaitMillis;

    /**
     * 构造参数。
     *
     * @param builder 构建器
     */
    private EventChannelOptions(Builder builder) {
        this.corePoolSize = builder.corePoolSize;
        this.maxPoolSize = builder.maxPoolSize;
        this.keepAliveSeconds = builder.keepAliveSeconds;
        this.queueCapacity = builder.queueCapacity;
        this.pendingCapacity = builder.pendingCapacity;
        this.shutdownAwaitMillis = builder.shutdownAwaitMillis;
    }

    /**
     * 构造默认参数。
     *
     * @return 默认参数
     */
    public static EventChannelOptions defaults() {
        return builder().build();
    }

    /**
     * 创建构建器。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 获取核心线程数。
     *
     * @return 核心线程数
     */
    public int getCorePoolSize() {
        return corePoolSize;
    }

    /**
     * 获取最大线程数。
     *
     * @return 最大线程数
     */
    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    /**
     * 获取空闲回收时间（秒）。
     *
     * @return 空闲回收时间
     */
    public long getKeepAliveSeconds() {
        return keepAliveSeconds;
    }

    /**
     * 获取广播队列容量。
     *
     * @return 队列容量
     */
    public int getQueueCapacity() {
        return queueCapacity;
    }

    /**
     * 获取启动期缓冲容量。
     *
     * @return 缓冲容量
     */
    public int getPendingCapacity() {
        return pendingCapacity;
    }

    /**
     * 获取关闭等待时间（毫秒）。
     *
     * @return 等待时间
     */
    public long getShutdownAwaitMillis() {
        return shutdownAwaitMillis;
    }

    /**
     * 事件通道参数构建器。
     *
     * @author zcd
     */
    public static final class Builder {

        /** 核心线程数。 */
        private int corePoolSize = DEFAULT_CORE_POOL_SIZE;

        /** 最大线程数。 */
        private int maxPoolSize = DEFAULT_MAX_POOL_SIZE;

        /** 空闲回收时间（秒）。 */
        private long keepAliveSeconds = DEFAULT_KEEP_ALIVE_SECONDS;

        /** 广播队列容量。 */
        private int queueCapacity = DEFAULT_QUEUE_CAPACITY;

        /** 启动期缓冲容量。 */
        private int pendingCapacity = DEFAULT_PENDING_CAPACITY;

        /** 关闭等待时间（毫秒）。 */
        private long shutdownAwaitMillis = DEFAULT_SHUTDOWN_AWAIT_MILLIS;

        /**
         * 设置核心线程数。
         *
         * @param corePoolSize 核心线程数，必须大于 0
         * @return 构建器自身
         */
        public Builder corePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
            return this;
        }

        /**
         * 设置最大线程数。
         *
         * @param maxPoolSize 最大线程数，必须不小于核心线程数
         * @return 构建器自身
         */
        public Builder maxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
            return this;
        }

        /**
         * 设置空闲回收时间。
         *
         * @param keepAliveSeconds 空闲回收时间（秒），必须大于 0
         * @return 构建器自身
         */
        public Builder keepAliveSeconds(long keepAliveSeconds) {
            this.keepAliveSeconds = keepAliveSeconds;
            return this;
        }

        /**
         * 设置广播队列容量。
         *
         * @param queueCapacity 队列容量，必须大于 0
         * @return 构建器自身
         */
        public Builder queueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
            return this;
        }

        /**
         * 设置启动期缓冲容量。
         *
         * @param pendingCapacity 缓冲容量，必须大于 0
         * @return 构建器自身
         */
        public Builder pendingCapacity(int pendingCapacity) {
            this.pendingCapacity = pendingCapacity;
            return this;
        }

        /**
         * 设置关闭等待时间。
         *
         * @param shutdownAwaitMillis 等待时间（毫秒），必须大于 0
         * @return 构建器自身
         */
        public Builder shutdownAwaitMillis(long shutdownAwaitMillis) {
            this.shutdownAwaitMillis = shutdownAwaitMillis;
            return this;
        }

        /**
         * 构建参数对象。
         *
         * @return 事件通道参数
         * @throws JellyfishException 参数非法时抛出
         */
        public EventChannelOptions build() {
            requirePositive(corePoolSize, "corePoolSize");
            requirePositive(maxPoolSize, "maxPoolSize");
            requirePositive(keepAliveSeconds, "keepAliveSeconds");
            requirePositive(queueCapacity, "queueCapacity");
            requirePositive(pendingCapacity, "pendingCapacity");
            requirePositive(shutdownAwaitMillis, "shutdownAwaitMillis");
            if (maxPoolSize < corePoolSize) {
                throw new JellyfishException("maxPoolSize must not be less than corePoolSize");
            }
            return new EventChannelOptions(this);
        }

        /**
         * 校验整型参数为正。
         *
         * @param value 参数值
         * @param name  参数名
         */
        private static void requirePositive(long value, String name) {
            if (value <= 0L) {
                throw new JellyfishException(name + " must be positive but was " + value);
            }
        }
    }
}
