package zcd.jellyfish.cli;

/**
 * 启动参数：不可变值对象，通过 {@link #builder(Mode)} 构建。
 * <p>
 * <b>为什么单独一个值对象</b>：参数是「进程级事实」，一旦解析完就不再变化；把它固化成不可变对象后，
 * {@link Launcher}、各 {@link zcd.jellyfish.cli.mode.RunMode}、{@link SessionBootstrap} 都只读它，
 * 不再各自去翻 {@code String[] args}，参数语义只有一份定义。
 * <p>
 * <b>为什么与内核对象零耦合</b>：本类只做「参数的形状」，不解析模型、不建会话、不读配置——
 * 那些事发生在 {@link SessionBootstrap} 与各模式里，因此参数解析可以独立单测。
 *
 * @author zcd
 */
public final class StartupOptions {

    /** 服务器模式的缺省端口。 */
    public static final int DEFAULT_PORT = 9096;

    /** 服务器模式的缺省绑定地址：只绑回环，避免「启动即对外」。 */
    public static final String DEFAULT_HOST = "127.0.0.1";

    /**
     * 启动模式。
     * <p>
     * 三种模式共享同一个 {@code main}、同一份 DI 装配与同一个 {@code AgentHarness}，只靠启动参数区分；
     * 差别在「谁来驱动 ReAct 回合」与「结果往哪里去」。
     */
    public enum Mode {

        /** 单次调用、不交互：进一个输入，出一次结果，进程退出。 */
        CLI("-cli"),

        /** 交互式终端界面（TamboUI），交互式多轮对话、命令补全与审批浮层。 */
        TUI("-tui"),

        /** HTTP 服务（Undertow），对外暴露能力接口，尚未实现。 */
        SERVER("-server");

        /** 命令行旗标。 */
        private final String flag;

        /**
         * 构造模式枚举。
         *
         * @param flag 命令行旗标
         */
        Mode(String flag) {
            this.flag = flag;
        }

        /**
         * 获取命令行旗标。
         *
         * @return 旗标（如 {@code "-cli"}）
         */
        public String getFlag() {
            return flag;
        }
    }

    /** 启动模式。 */
    private final Mode mode;

    /** 单次模式输入（{@code -p}）；{@code null} 表示从 stdin 读到 EOF。 */
    private final String prompt;

    /** 要切换到的已有会话标识；{@code null} 表示不指定。 */
    private final String sessionId;

    /** 新建会话时绑定的 agent 标识；{@code null} 表示按默认 agent。 */
    private final String agentId;

    /** 新建会话时指定的 provider；{@code null} 表示跟随默认。 */
    private final String provider;

    /** 新建会话时指定的模型；{@code null} 表示跟随默认。 */
    private final String model;

    /** 服务器模式端口。 */
    private final int port;

    /** 服务器模式绑定地址。 */
    private final String host;

    /** 服务器模式 API key；{@code null} 表示不鉴权。 */
    private final String apiKey;

    /** 是否展示思考过程（cli 模式打到 stderr，tui 模式决定启动时的展开状态）。 */
    private final boolean showThinking;

    /**
     * 是否在 cli 模式的工具轨迹行上打出调用参数。
     * <p>
     * 只归 {@code -cli}：互动界面已有运行期开关（{@code Ctrl+E} / {@code /toolargs}），
     * 而单次模式没有交互窗口，想看「模型到底要跑什么」就只剩启动参数这一条路。
     */
    private final boolean showToolArgs;

    /** 是否把日志级别降到 DEBUG。 */
    private final boolean verbose;

    /**
     * 是否信任并加载项目级配置。
     * <p>
     * 项目级配置按当前目录读取，因此「在哪个仓库里启动」决定它的内容，而它能改 provider 的
     * {@code baseUrl}/{@code apiKey}、新增 agent、改落盘目录——所以默认不加载。
     * 本参数是「我确认这个目录可信」的那一次显式表态，只对本次进程有效。
     */
    private final boolean trustProjectConfig;

    /** 是否请求帮助。 */
    private final boolean help;

    /** 是否请求版本号。 */
    private final boolean version;

    /**
     * 由构建器构造。
     *
     * @param builder 参数构建器
     */
    private StartupOptions(Builder builder) {
        this.mode = builder.mode;
        this.prompt = builder.prompt;
        this.sessionId = builder.sessionId;
        this.agentId = builder.agentId;
        this.provider = builder.provider;
        this.model = builder.model;
        this.port = builder.port;
        this.host = builder.host;
        this.apiKey = builder.apiKey;
        this.showThinking = builder.showThinking;
        this.showToolArgs = builder.showToolArgs;
        this.verbose = builder.verbose;
        this.trustProjectConfig = builder.trustProjectConfig;
        this.help = builder.help;
        this.version = builder.version;
    }

    /**
     * 创建参数构建器，端口与绑定地址取缺省值。
     *
     * @param mode 启动模式，不可为 {@code null}
     * @return 参数构建器
     */
    public static Builder builder(Mode mode) {
        return new Builder(mode);
    }

    /**
     * 获取启动模式。
     *
     * @return 启动模式
     */
    public Mode getMode() {
        return mode;
    }

    /**
     * 获取单次模式输入。
     *
     * @return 输入文本，未指定时为 {@code null}
     */
    public String getPrompt() {
        return prompt;
    }

    /**
     * 获取要切换到的已有会话标识。
     *
     * @return 会话标识，未指定时为 {@code null}
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 获取新建会话要绑定的 agent 标识。
     *
     * @return agent 标识，未指定时为 {@code null}
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * 获取新建会话要指定的 provider。
     *
     * @return provider 名，未指定时为 {@code null}
     */
    public String getProvider() {
        return provider;
    }

    /**
     * 获取新建会话要指定的模型。
     *
     * @return 模型名，未指定时为 {@code null}
     */
    public String getModel() {
        return model;
    }

    /**
     * 获取服务器模式端口。
     *
     * @return 端口
     */
    public int getPort() {
        return port;
    }

    /**
     * 获取服务器模式绑定地址。
     *
     * @return 绑定地址
     */
    public String getHost() {
        return host;
    }

    /**
     * 获取服务器模式 API key。
     *
     * @return API key；未指定时返回 {@code null}
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * 判断是否需要展示思考过程。
     *
     * @return 需要显示返回 {@code true}
     */
    public boolean isShowThinking() {
        return showThinking;
    }

    /**
     * 判断是否在 cli 模式的工具轨迹行上打出调用参数。
     *
     * @return 需要显示返回 {@code true}
     */
    public boolean isShowToolArgs() {
        return showToolArgs;
    }

    /**
     * 判断是否把日志级别降到 DEBUG。
     *
     * @return 需要详细日志返回 {@code true}
     */
    public boolean isVerbose() {
        return verbose;
    }

    /**
     * 判断是否信任并加载项目级配置。
     *
     * @return 信任项目级配置返回 {@code true}
     */
    public boolean isTrustProjectConfig() {
        return trustProjectConfig;
    }

    /**
     * 判断是否请求帮助。
     *
     * @return 请求帮助返回 {@code true}
     */
    public boolean isHelp() {
        return help;
    }

    /**
     * 判断是否请求版本号。
     *
     * @return 请求版本号返回 {@code true}
     */
    public boolean isVersion() {
        return version;
    }

    /**
     * 参数构建器：只由 {@link StartupOptionsParser} 使用。
     * <p>
     * 参数项多且大多可选，用构建器避免十几个位置参数在调用点错位；与 {@code LlmRequest} 的构建风格一致。
     *
     * @author zcd
     */
    public static final class Builder {

        /** 启动模式（不可为 {@code null}；裸跑时由 {@link StartupOptionsParser} 填为 {@code TUI}）。 */
        private final Mode mode;

        /** 单次模式输入。 */
        private String prompt;

        /** 要切换到的已有会话标识。 */
        private String sessionId;

        /** 新建会话要绑定的 agent 标识。 */
        private String agentId;

        /** 新建会话要指定的 provider。 */
        private String provider;

        /** 新建会话要指定的模型。 */
        private String model;

        /** 服务器模式端口。 */
        private int port = DEFAULT_PORT;

        /** 服务器模式绑定地址。 */
        private String host = DEFAULT_HOST;

        /** API key；{@code null} 表示不鉴权。 */
        private String apiKey;

        /** 是否展示思考过程。 */
        private boolean showThinking;

        /** 是否在 cli 模式的工具轨迹行上打出调用参数。 */
        private boolean showToolArgs;

        /** 是否把日志级别降到 DEBUG。 */
        private boolean verbose;

        /** 是否信任并加载项目级配置。 */
        private boolean trustProjectConfig;

        /** 是否请求帮助。 */
        private boolean help;

        /** 是否请求版本号。 */
        private boolean version;

        /**
         * 构造构建器。
         *
         * @param mode 启动模式，不可为 {@code null}
         */
        private Builder(Mode mode) {
            this.mode = mode;
        }

        /**
         * 设置单次模式输入。
         *
         * @param prompt 输入文本，可为 {@code null}
         * @return 本构建器
         */
        public Builder prompt(String prompt) {
            this.prompt = prompt;
            return this;
        }

        /**
         * 设置要切换到的已有会话标识。
         *
         * @param sessionId 会话标识，可为 {@code null}
         * @return 本构建器
         */
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /**
         * 设置新建会话要绑定的 agent 标识。
         *
         * @param agentId agent 标识，可为 {@code null}
         * @return 本构建器
         */
        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        /**
         * 设置新建会话要指定的 provider 与模型。
         *
         * @param provider provider 名，可为 {@code null}
         * @param model    模型名，可为 {@code null}
         * @return 本构建器
         */
        public Builder model(String provider, String model) {
            this.provider = provider;
            this.model = model;
            return this;
        }

        /**
         * 设置服务器模式端口。
         *
         * @param port 端口
         * @return 本构建器
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * 设置服务器模式绑定地址。
         *
         * @param host 绑定地址，可为 {@code null}（回退到缺省值）
         * @return 本构建器
         */
        public Builder host(String host) {
            this.host = host == null ? DEFAULT_HOST : host;
            return this;
        }

        /**
         * 设置服务器模式的 API key。
         *
         * @param apiKey API key；{@code null} 或空白表示不鉴权
         * @return 本构建器
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * 设置是否展示思考过程。
         *
         * @param showThinking 需要展示传 {@code true}
         * @return 本构建器
         */
        public Builder showThinking(boolean showThinking) {
            this.showThinking = showThinking;
            return this;
        }

        /**
         * 设置是否在 cli 模式的工具轨迹行上打出调用参数。
         *
         * @param showToolArgs 需要显示传 {@code true}
         * @return 本构建器
         */
        public Builder showToolArgs(boolean showToolArgs) {
            this.showToolArgs = showToolArgs;
            return this;
        }

        /**
         * 设置是否把日志级别降到 DEBUG。
         *
         * @param verbose 需要详细日志传 {@code true}
         * @return 本构建器
         */
        public Builder verbose(boolean verbose) {
            this.verbose = verbose;
            return this;
        }

        /**
         * 设置是否信任并加载项目级配置。
         *
         * @param trustProjectConfig 信任项目级配置传 {@code true}
         * @return 本构建器
         */
        public Builder trustProjectConfig(boolean trustProjectConfig) {
            this.trustProjectConfig = trustProjectConfig;
            return this;
        }

        /**
         * 设置是否请求帮助。
         *
         * @param help 请求帮助传 {@code true}
         * @return 本构建器
         */
        public Builder help(boolean help) {
            this.help = help;
            return this;
        }

        /**
         * 设置是否请求版本号。
         *
         * @param version 请求版本号传 {@code true}
         * @return 本构建器
         */
        public Builder version(boolean version) {
            this.version = version;
            return this;
        }

        /**
         * 构建参数对象。
         *
         * @return 不可变的启动参数
         */
        public StartupOptions build() {
            return new StartupOptions(this);
        }
    }
}
