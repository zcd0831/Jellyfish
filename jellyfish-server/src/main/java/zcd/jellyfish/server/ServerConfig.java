package zcd.jellyfish.server;

/**
 * Server 外壳的运行参数：绑定地址、并发与体积上限。
 * <p>
 * <b>为什么不直接用 cli 的 {@code StartupOptions}</b>：那会让 {@code jellyfish-server} 依赖
 * {@code jellyfish-cli}，形成 {@code cli → server → cli} 循环。这里只保留「Server 真正需要的那几项」，
 * 由 {@code ServerRunMode} 从 {@code StartupOptions} 映射过来——映射本身很薄，而依赖方向不能反转。
 * <p>
 * <b>没有「新建会话的缺省 agent / 模型」</b>：那两项由调用方在
 * {@code POST /sessions} 的请求体里直接给定，不给就在内核里按配置默认值解析（模型默认值归
 * {@code models.json}，agent 默认值恒为内置）。服务端再存一份启动参数默认值，只会多出
 * 「到底哪一层生效」这本账。
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

    /**
     * 单次写 socket 的缺省时限（秒）。
     * <p>
     * <b>它是「连上不读」的客户端的唯一出口</b>：SSE 的写是阻塞写，客户端不读时 socket 缓冲区填满，
     * 这条流唯一的写线程（也是它唯一的消费者，连 keepalive 都由它发）就停在 {@code write} 里——
     * 于是既没人发现它卡住、也没人归还 {@code streamPermit}。缺省 {@code maxStreams=16}，
     * 16 个这样的连接就能让之后所有 {@code /chat} 一律 503。
     * <p>
     * <b>必须大于 {@link #DEFAULT_KEEPALIVE_SECONDS}</b>：活着的流每 15 秒有一帧 keepalive 要写，
     * 时限比它短就会把正常的流也判死——实测（`StalledSseClientTest`）证明它不只约束「一次写多久没写完」，
     * 也约束「两帧之间最长静默多久」：静默超过时限的连接会被判死并关掉。缺省的 60 秒对 15 秒的
     * keepalive 留了 4 倍余量。
     */
    public static final int DEFAULT_WRITE_TIMEOUT_SECONDS = 60;

    /**
     * 提供 API key 的环境变量名。
     * <p>
     * <b>为什么把环境变量名定义在这里</b>：它是「这个服务的密钥从哪来」的一部分，而不是某一侧的私事——
     * 启动参数解析（显示用法）与启动模式（真正读取）都要用它，两处各写一遍字面量迟早漂移成
     * 「帮助里写的变量名不是实际读的那个」。
     * <p>
     * <b>为什么推荐环境变量而不是命令行参数</b>：argv 会出现在 {@code ps} 输出里，同机器上的其他用户
     * 能直接看到密钥；命令行参数仍然支持，只是相对更差的那一档。
     */
    public static final String ENV_API_KEY = "JELLYFISH_SERVER_API_KEY";

    /**
     * 建议的最短 API key 长度。
     * <p>
     * 低于它不拒绝启动、只告警：密钥长度是用户自己的安全权衡，而这个服务默认只绑回环——
     * 把它做成硬校验，只会让「在局域网里试一下」变成一件要读文档才能做的事。
     */
    public static final int MIN_RECOMMENDED_API_KEY_LENGTH = 16;

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

    /** 单次写 socket 的时限（秒）。 */
    private final int writeTimeoutSeconds;

    /** Undertow 工作线程数。 */
    private final int workerThreads;

    /** API key；{@code null} 表示不启用鉴权。 */
    private final String apiKey;

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
        this.writeTimeoutSeconds = builder.writeTimeoutSeconds;
        this.workerThreads = builder.workerThreads;
        this.apiKey = blankToNull(builder.apiKey);
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
     * 获取单次写 socket 的时限。
     *
     * @return 时限秒数
     */
    public int getWriteTimeoutSeconds() {
        return writeTimeoutSeconds;
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
     * 获取 API key。
     * <p>
     * {@code null} 表示**不鉴权**（任何能访问该端口的人都能建会话、跑命令、读全部会话正文）。
     * 这是刻意的缺省：本服务默认只绑 {@code 127.0.0.1}，而对回环还要求先配密钥，
     * 只会把「本地跑一次」变成一件要读文档才能做的事。对外开放必须显式配密钥。
     *
     * @return API key；未启用鉴权时返回 {@code null}
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * 把空白归一成 {@code null}。
     * <p>
     * 空串与 {@code null} 必须同义：否则「配了个空密钥」会变成「鉴权开着但谁都过不了」，
     * 而现场表现是「服务起来了但客户端全 401」——那是本类里最难看的一种失败形态。
     *
     * @param value 原值，可为 {@code null}
     * @return 去空白后的值；空白时返回 {@code null}
     */
    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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

        /** 单次写 socket 的时限（秒）。 */
        private int writeTimeoutSeconds = DEFAULT_WRITE_TIMEOUT_SECONDS;

        /** 工作线程数。 */
        private int workerThreads = defaultWorkerThreads();

        /** API key；{@code null} 表示不鉴权。 */
        private String apiKey;

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
         * 设置 API key。
         *
         * @param apiKey API key；{@code null} 或空白表示不鉴权
         * @return 本构建器
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
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
         * 设置单次写 socket 的时限。
         *
         * @param writeTimeoutSeconds 时限秒数
         * @return 本构建器
         */
        public Builder writeTimeoutSeconds(int writeTimeoutSeconds) {
            this.writeTimeoutSeconds = writeTimeoutSeconds;
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
