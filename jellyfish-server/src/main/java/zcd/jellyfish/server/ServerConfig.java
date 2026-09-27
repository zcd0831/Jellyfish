package zcd.jellyfish.server;

import zcd.jellyfish.api.extension.PermissionMode;

/**
 * Server 外壳的运行参数：绑定地址、并发与体积上限、新建会话的缺省值。
 * <p>
 * <b>为什么不直接用 cli 的 {@code StartupOptions}</b>：那会让 {@code jellyfish-server} 依赖
 * {@code jellyfish-cli}，形成 {@code cli → server → cli} 循环。这里只保留「Server 真正需要的那几项」，
 * 由 {@code ServerRunMode} 从 {@code StartupOptions} 映射过来——映射本身很薄，而依赖方向不能反转。
 * <p>
 * <b>为什么缺省值写在这里而不是读配置</b>：这些都是「外壳级」开关（绑哪个口、一次最多几个流），
 * 与内核配置（models / agents / react）无关；它们唯一的外部来源是命令行。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ServerConfig {

    /** 请求体缺省上限：1 MiB。 */
    public static final int DEFAULT_MAX_BODY_BYTES = 1024 * 1024;

    /** 并发 SSE 流缺省上限。 */
    public static final int DEFAULT_MAX_STREAMS = 16;

    /** keepalive 注释帧缺省间隔（秒）。 */
    public static final int DEFAULT_KEEPALIVE_SECONDS = 15;

    /** 绑定地址。 */
    private final String host;

    /** 端口。 */
    private final int port;

    /** 请求体字节上限。 */
    private final int maxBodyBytes;

    /** 并发 SSE 流上限。 */
    private final int maxStreams;

    /** keepalive 间隔（秒）。 */
    private final int keepaliveSeconds;

    /** Undertow 工作线程数。 */
    private final int workerThreads;

    /** 新建会话的缺省 agentId，可为 {@code null}。 */
    private final String defaultAgentId;

    /** 新建会话的缺省 provider，可为 {@code null}。 */
    private final String defaultProvider;

    /** 新建会话的缺省模型，可为 {@code null}。 */
    private final String defaultModel;

    /** 新建会话的缺省权限模式，可为 {@code null}（按 NORMAL）。 */
    private final PermissionMode defaultPermissionMode;

    /**
     * 由构建器构造。
     *
     * @param builder 构建器
     */
    private ServerConfig(Builder builder) {
        this.host = builder.host;
        this.port = builder.port;
        this.maxBodyBytes = builder.maxBodyBytes;
        this.maxStreams = builder.maxStreams;
        this.keepaliveSeconds = builder.keepaliveSeconds;
        this.workerThreads = builder.workerThreads;
        this.defaultAgentId = builder.defaultAgentId;
        this.defaultProvider = builder.defaultProvider;
        this.defaultModel = builder.defaultModel;
        this.defaultPermissionMode = builder.defaultPermissionMode;
    }

    /**
     * 创建构建器，取全部缺省值。
     *
     * @param host 绑定地址
     * @param port 端口
     * @return 构建器
     */
    public static Builder builder(String host, int port) {
        return new Builder(host, port);
    }

    /**
     * 获取绑定地址。
     *
     * @return 绑定地址
     */
    public String getHost() {
        return host;
    }

    /**
     * 获取端口。
     *
     * @return 端口
     */
    public int getPort() {
        return port;
    }

    /**
     * 获取请求体字节上限。
     *
     * @return 上限
     */
    public int getMaxBodyBytes() {
        return maxBodyBytes;
    }

    /**
     * 获取并发 SSE 流上限。
     *
     * @return 上限
     */
    public int getMaxStreams() {
        return maxStreams;
    }

    /**
     * 获取 keepalive 间隔。
     *
     * @return 间隔秒数
     */
    public int getKeepaliveSeconds() {
        return keepaliveSeconds;
    }

    /**
     * 获取工作线程数。
     *
     * @return 线程数
     */
    public int getWorkerThreads() {
        return workerThreads;
    }

    /**
     * 获取缺省 agentId。
     *
     * @return agentId，可能为 {@code null}
     */
    public String getDefaultAgentId() {
        return defaultAgentId;
    }

    /**
     * 获取缺省 provider。
     *
     * @return provider，可能为 {@code null}
     */
    public String getDefaultProvider() {
        return defaultProvider;
    }

    /**
     * 获取缺省模型。
     *
     * @return 模型，可能为 {@code null}
     */
    public String getDefaultModel() {
        return defaultModel;
    }

    /**
     * 获取缺省权限模式。
     *
     * @return 权限模式，可能为 {@code null}
     */
    public PermissionMode getDefaultPermissionMode() {
        return defaultPermissionMode;
    }

    /**
     * 运行参数构建器。
     *
     * @author zcd
     */
    public static final class Builder {

        /** 绑定地址。 */
        private final String host;

        /** 端口。 */
        private final int port;

        /** 请求体字节上限。 */
        private int maxBodyBytes = DEFAULT_MAX_BODY_BYTES;

        /** 并发 SSE 流上限。 */
        private int maxStreams = DEFAULT_MAX_STREAMS;

        /** keepalive 间隔（秒）。 */
        private int keepaliveSeconds = DEFAULT_KEEPALIVE_SECONDS;

        /** 工作线程数。 */
        private int workerThreads = defaultWorkerThreads();

        /** 缺省 agentId。 */
        private String defaultAgentId;

        /** 缺省 provider。 */
        private String defaultProvider;

        /** 缺省模型。 */
        private String defaultModel;

        /** 缺省权限模式。 */
        private PermissionMode defaultPermissionMode;

        /**
         * 构造构建器。
         *
         * @param host 绑定地址
         * @param port 端口
         */
        private Builder(String host, int port) {
            this.host = host;
            this.port = port;
        }

        /**
         * 设置请求体字节上限。
         *
         * @param maxBodyBytes 上限
         * @return 本构建器
         */
        public Builder maxBodyBytes(int maxBodyBytes) {
            this.maxBodyBytes = maxBodyBytes;
            return this;
        }

        /**
         * 设置并发 SSE 流上限。
         *
         * @param maxStreams 上限
         * @return 本构建器
         */
        public Builder maxStreams(int maxStreams) {
            this.maxStreams = maxStreams;
            return this;
        }

        /**
         * 设置 keepalive 间隔。
         *
         * @param keepaliveSeconds 间隔秒数
         * @return 本构建器
         */
        public Builder keepaliveSeconds(int keepaliveSeconds) {
            this.keepaliveSeconds = keepaliveSeconds;
            return this;
        }

        /**
         * 设置工作线程数。
         *
         * @param workerThreads 线程数
         * @return 本构建器
         */
        public Builder workerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
            return this;
        }

        /**
         * 设置新建会话的缺省值。
         *
         * @param agentId        agentId，可为 {@code null}
         * @param provider       provider，可为 {@code null}
         * @param model          模型，可为 {@code null}
         * @param permissionMode 权限模式，可为 {@code null}
         * @return 本构建器
         */
        public Builder sessionDefaults(String agentId, String provider, String model,
                                       PermissionMode permissionMode) {
            this.defaultAgentId = agentId;
            this.defaultProvider = provider;
            this.defaultModel = model;
            this.defaultPermissionMode = permissionMode;
            return this;
        }

        /**
         * 构建运行参数。
         *
         * @return 不可变的运行参数
         */
        public ServerConfig build() {
            return new ServerConfig(this);
        }

        /**
         * 计算缺省工作线程数。
         * <p>
         * 下限 8 是为了让「若干条长回合 + 一些短查询」并存时短查询仍然有人应答；
         * 上限 64 是防止在超大核数机器上开出一大堆只用于 HTTP 的线程。
         *
         * @return 线程数
         */
        private static int defaultWorkerThreads() {
            int cores = Runtime.getRuntime().availableProcessors();
            return Math.max(8, Math.min(64, cores * 2));
        }
    }
}
