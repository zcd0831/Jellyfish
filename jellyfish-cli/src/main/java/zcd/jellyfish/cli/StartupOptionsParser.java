package zcd.jellyfish.cli;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.server.ServerConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动参数解析器：把 {@code String[] args} 解析成 {@link StartupOptions}。
 * <p>
 * <b>为什么手写</b>：参数表只有十几个，且都是简单取值 / 开关；为此引入命令行框架收益很低，
 * 反而给 JDK 1.8 的构建引入新依赖。手写解析无状态、无 IO，可独立单测。
 * <p>
 * <b>为什么「只给裸跑兜底、其余不猜」</b>：一个参数都不带的 {@code jellyfish} 默认 {@code -tui}——
 * 交互界面是最顺手的入口，不该逼用户先记住 {@code -tui} 怎么拼。但兜底只此一处：<b>只要带了参数
 * （哪怕只是一个 {@code --verbose}），就不再替用户猜意图</b>，仍旧报「请指定启动模式」。
 * 刻意不做「默认 {@code -cli}」：那会让「参数写错了」这种情形悄悄退化成「卡在等 stdin EOF」，
 * 比一条明说的用法错误难排查得多。
 * <p>
 * <b>为什么失败走异常</b>：解析失败属于「用户输入错误」，需要把「哪里错了」原样带给调用方；
 * 统一抛 {@link JellyfishException}（仓库约定），由 {@code main} 打印用法后退
 * {@link ExitCodes#USAGE_ERROR}。
 *
 * @author zcd
 */
public final class StartupOptionsParser {

    /** 模式旗标：CLI。 */
    private static final String FLAG_CLI = "-cli";

    /** 模式旗标：TUI。 */
    private static final String FLAG_TUI = "-tui";

    /** 模式旗标：Server。 */
    private static final String FLAG_SERVER = "-server";

    /** 帮助旗标。 */
    private static final String FLAG_HELP_SHORT = "-h";

    /** 帮助长旗标。 */
    private static final String FLAG_HELP_LONG = "--help";

    /** 版本旗标。 */
    private static final String FLAG_VERSION = "-V";

    /** 版本长旗标。 */
    private static final String FLAG_VERSION_LONG = "--version";

    /** 单次输入旗标。 */
    private static final String FLAG_PROMPT_SHORT = "-p";

    /** 单次输入长旗标。 */
    private static final String FLAG_PROMPT_LONG = "--print";

    /** 会话旗标。 */
    private static final String FLAG_SESSION = "--session";

    /** agent 旗标。 */
    private static final String FLAG_AGENT = "--agent";

    /** 模型旗标。 */
    private static final String FLAG_MODEL = "--model";

    /** 端口旗标。 */
    private static final String FLAG_PORT = "--port";

    /** 绑定地址旗标。 */
    private static final String FLAG_HOST = "--host";

    /** API key 旗标。 */
    private static final String FLAG_API_KEY = "--api-key";

    /** 思考过程旗标。 */
    private static final String FLAG_SHOW_THINKING = "--show-thinking";

    /** 工具调用参数旗标。 */
    private static final String FLAG_SHOW_TOOL_ARGS = "--show-tool-args";

    /** 详细日志旗标。 */
    private static final String FLAG_VERBOSE = "--verbose";

    /** 信任项目级配置旗标。 */
    private static final String FLAG_TRUST_PROJECT_CONFIG = "--trust-project-config";

    /** 端口上界。 */
    private static final int MAX_PORT = 65535;

    /** 用法文本。 */
    private static final String USAGE = ""
            + "用法：jellyfish <模式> [选项]\n"
            + "\n"
            + "模式（三选一；一个参数都不带时默认 -tui）：\n"
            + "  -cli                  单次调用、不交互：进一个输入，出一次结果后退出\n"
            + "  -tui                  交互式终端界面\n"
            + "  -server [端口]        以 HTTP 服务运行，端口缺省 "
            + StartupOptions.DEFAULT_PORT + "\n"
            + "\n"
            + "选项（未标注适用模式的三种模式通用；标注了的只在对应模式下被接受）：\n"
            + "  -p, --print <输入>      单次模式的输入；缺省时从 stdin 读到 EOF（仅 -cli）\n"
            + "      --session <会话>    切换到已有会话（-cli / -tui）\n"
            + "      --agent <agentId>   新会话绑定的 agent（仅 -cli；TUI 用 /agent，Server 用 POST /sessions）\n"
            + "      --model <provider/模型>\n"
            + "                          新会话指定的模型，必须含 \"/\"（仅 -cli；TUI 用 /model）\n"
            + "      --port <端口>       服务器端口（仅 -server，等价于 -server 的位置参数）\n"
            + "      --host <地址>       服务器绑定地址（仅 -server），缺省 " + StartupOptions.DEFAULT_HOST + "\n"
            + "      --api-key <密钥>    服务器 API key（仅 -server）；不配则不鉴权，也可用环境变量\n"
            + "                          " + ServerConfig.ENV_API_KEY + "（推荐：argv 会出现在 ps 输出里）\n"
            + "      --show-thinking     在 -cli 的工具轨迹之外单独打出思考过程（仅 -cli；TUI 用 Ctrl+T）\n"
            + "      --show-tool-args    在 -cli 的工具轨迹行上打出调用参数（单行，过长截断；可能含敏感信息）\n"
            + "      --trust-project-config\n"
            + "                          信任并加载项目级配置（./.jellyfish/*.json），仅本次进程有效。\n"
            + "                          默认不加载：它按当前目录读取，能改模型端点与密钥、新增 agent、\n"
            + "                          改落盘目录，因此一个 clone 下来的仓库就足以改变运行行为。\n"
            + "                          在 -tui 下改由启动时的确认框询问；确认过的内容会记进\n"
            + "                          ~/.jellyfish/trusted-project-configs.json，内容一变即失效\n"
            + "      --verbose           日志级别降到 DEBUG\n"
            + "  -h, --help              显示本帮助\n"
            + "  -V, --version           显示版本号\n"
            + "\n"
            + "输出约定：回答与命令结果走 stdout，诊断、工具进度与日志走 stderr。\n"
            + "退出码：0 成功，2 用法错误，3 启动失败，4 运行失败，6 回合未收敛。";

    private StartupOptionsParser() {
    }

    /**
     * 获取用法文本。
     *
     * @return 用法文本
     */
    public static String usage() {
        return USAGE;
    }

    /**
     * 解析启动参数。
     * <p>
     * {@code -h} / {@code -V} 优先于其它校验：只要请求了帮助或版本，就不再追究「模式没给」这类问题，
     * 让 {@code jellyfish -h} 永远能出帮助。
     * <p>
     * <b>裸跑兜底</b>：{@code args} 为空（含 {@code null}）时模式取 {@link StartupOptions.Mode#TUI}。
     * 这是唯一一处「替用户选模式」，判据只有「参数个数为零」这一个事实，不掺任何内容推断。
     *
     * @param args 命令行参数，可为 {@code null}
     * @return 启动参数，保证非 {@code null}
     * @throws JellyfishException 参数缺失、未知、重复或取值非法时抛出
     */
    public static StartupOptions parse(String[] args) {
        String[] raw = args == null ? new String[0] : args;
        Cursor cursor = new Cursor(raw);
        // 裸跑默认进交互界面：敲一下程序名就该能用，而不是先吃到一条用法错误再去查 -tui 怎么拼。
        // 只兜底「零参数」这一种情形——判据写死在参数个数上，以后也不会因为多认出一个开关而悄悄放宽。
        StartupOptions.Mode mode = raw.length == 0 ? StartupOptions.Mode.TUI : null;
        String prompt = null;
        String sessionId = null;
        String agentId = null;
        String provider = null;
        String model = null;
        Integer portOption = null;
        String hostOption = null;
        String apiKeyOption = null;
        boolean showThinking = false;
        boolean showToolArgs = false;
        boolean verbose = false;
        boolean trustProjectConfig = false;
        boolean help = false;
        boolean version = false;
        List<String> positionals = new ArrayList<String>();
        while (cursor.hasNext()) {
            String arg = cursor.next();
            if (arg == null) {
                throw new JellyfishException("参数不能为 null（第 " + cursor.position() + " 个）");
            }
            if (FLAG_CLI.equals(arg) || FLAG_TUI.equals(arg) || FLAG_SERVER.equals(arg)) {
                mode = requireSingleMode(mode, arg);
            } else if (FLAG_HELP_SHORT.equals(arg) || FLAG_HELP_LONG.equals(arg)) {
                help = true;
            } else if (FLAG_VERSION.equals(arg) || FLAG_VERSION_LONG.equals(arg)) {
                version = true;
            } else if (FLAG_SHOW_THINKING.equals(arg)) {
                showThinking = true;
            } else if (FLAG_SHOW_TOOL_ARGS.equals(arg)) {
                showToolArgs = true;
            } else if (FLAG_VERBOSE.equals(arg)) {
                verbose = true;
            } else if (FLAG_TRUST_PROJECT_CONFIG.equals(arg)) {
                trustProjectConfig = true;
            } else if (FLAG_PROMPT_SHORT.equals(arg) || FLAG_PROMPT_LONG.equals(arg)) {
                prompt = cursor.requireValue(arg);
            } else if (FLAG_SESSION.equals(arg)) {
                sessionId = requireNonBlank(arg, cursor.requireValue(arg));
            } else if (FLAG_AGENT.equals(arg)) {
                agentId = requireNonBlank(arg, cursor.requireValue(arg));
            } else if (FLAG_MODEL.equals(arg)) {
                String[] split = splitModel(cursor.requireValue(arg));
                provider = split[0];
                model = split[1];
            } else if (FLAG_PORT.equals(arg)) {
                portOption = parsePort(FLAG_PORT, cursor.requireValue(arg));
            } else if (FLAG_HOST.equals(arg)) {
                hostOption = requireNonBlank(arg, cursor.requireValue(arg));
            } else if (FLAG_API_KEY.equals(arg)) {
                apiKeyOption = requireNonBlank(arg, cursor.requireValue(arg));
            } else if (arg.startsWith("--") && arg.indexOf('=') > 0) {
                throw new JellyfishException("不支持 --key=value 写法：" + arg + "（请写成 --key value）");
            } else if (arg.startsWith("-") && !"-".equals(arg)) {
                throw new JellyfishException(unknownArgumentMessage(arg));
            } else {
                positionals.add(arg);
            }
        }
        return build(mode, prompt, sessionId, agentId, provider, model, portOption, hostOption,
                apiKeyOption, showThinking, showToolArgs, verbose, trustProjectConfig, help, version, positionals);
    }

    /**
     * 汇总解析结果并做跨参数校验。
     *
     * @param mode           启动模式，可为 {@code null}
     * @param prompt         单次模式输入，可为 {@code null}
     * @param sessionId      会话标识，可为 {@code null}
     * @param agentId        agent 标识，可为 {@code null}
     * @param provider       provider 名，可为 {@code null}
     * @param model          模型名，可为 {@code null}
     * @param portOption     {@code --port} 取值，可为 {@code null}
     * @param hostOption     {@code --host} 取值，可为 {@code null}
     * @param showThinking   是否在 {@code -cli} 下单独打出思考过程
     * @param showToolArgs   是否在工具轨迹行上打出调用参数
     * @param verbose        是否详细日志
     * @param trustProjectConfig 是否信任并加载项目级配置
     * @param help           是否请求帮助
     * @param version        是否请求版本号
     * @param positionals    位置参数列表
     * @return 启动参数
     * @throws JellyfishException 参数组合非法时抛出
     */
    private static StartupOptions build(StartupOptions.Mode mode, String prompt, String sessionId, String agentId,
                                        String provider, String model,
                                        Integer portOption, String hostOption, String apiKeyOption,
                                        boolean showThinking, boolean showToolArgs, boolean verbose,
                                        boolean trustProjectConfig, boolean help, boolean version,
                                        List<String> positionals) {
        if (help || version) {
            // 帮助与版本不执行任何模式：模式只用于填一个合法值，避免为一个纯展示请求纠结「模式没给」
            StartupOptions.Mode displayMode = mode == null ? StartupOptions.Mode.CLI : mode;
            return StartupOptions.builder(displayMode)
                    .prompt(prompt).sessionId(sessionId).agentId(agentId).model(provider, model)
                    .port(StartupOptions.DEFAULT_PORT).host(hostOption)
                    .apiKey(apiKeyOption).showThinking(showThinking).showToolArgs(showToolArgs)
                    .verbose(verbose).trustProjectConfig(trustProjectConfig).help(help).version(version)
                    .build();
        }
        if (mode == null) {
            throw new JellyfishException("请指定启动模式：-cli / -tui / -server（用 -h 查看用法）");
        }
        requireCliOnly(mode, prompt != null, "-p / --print",
                "TUI 直接在界面里输入，Server 的对话走 HTTP 接口");
        requireCliOnly(mode, agentId != null, "--agent",
                "TUI 用 /agent 设置，Server 在 POST /sessions 请求体里指定");
        requireCliOnly(mode, provider != null || model != null, "--model",
                "TUI 用 /model 设置，Server 在 POST /sessions 请求体里指定");
        requireCliOnly(mode, showToolArgs, FLAG_SHOW_TOOL_ARGS,
                "TUI 用 Ctrl+E / /toolargs 在界面上切（同样是全局开关）");
        requireCliOnly(mode, showThinking, FLAG_SHOW_THINKING,
                "TUI 用 Ctrl+T / /thinking 在界面上切（同样是全局开关）");
        if (mode != StartupOptions.Mode.SERVER && (!positionals.isEmpty() || portOption != null
                || hostOption != null || apiKeyOption != null)) {
            throw new JellyfishException("只有 -server 支持端口、绑定地址与 --api-key");
        }
        if (mode == StartupOptions.Mode.SERVER && sessionId != null) {
            // Server 的会话由 HTTP path 显式寻址，启动参数指向单个会话没有意义。
            // 注意不要在这里承诺「--agent/--model 仍可用」：上面那两条 requireCliOnly 已经把它们
            // 判成用法错误了，兜底的一句错话正好会被用户当成出路照着试
            throw new JellyfishException("-server 不支持 --session：会话由 HTTP 接口按 id 寻址"
                    + "（新建会话的 agent 与模型由 POST /sessions 请求体给定）");
        }
        if (positionals.size() > 1) {
            throw new JellyfishException("位置参数过多：" + positionals);
        }
        Integer positionalPort = positionals.isEmpty() ? null : parsePort("端口", positionals.get(0));
        if (positionalPort != null && portOption != null && !positionalPort.equals(portOption)) {
            throw new JellyfishException("端口重复指定且不一致：位置参数 " + positionalPort + "，--port " + portOption);
        }
        int port = StartupOptions.DEFAULT_PORT;
        if (positionalPort != null) {
            port = positionalPort;
        } else if (portOption != null) {
            port = portOption;
        }
        return StartupOptions.builder(mode)
                .prompt(prompt).sessionId(sessionId).agentId(agentId).model(provider, model)
                .port(port).host(hostOption).apiKey(apiKeyOption)
                .showThinking(showThinking).showToolArgs(showToolArgs)
                .verbose(verbose).trustProjectConfig(trustProjectConfig)
                .help(help).version(version).build();
    }

    /**
     * 校验某个参数只在 CLI 下被接受。
     * <p>
     * <b>为什么拒绝而不是忽略</b>：{@code -tui --agent coder} 若被静默丢掉，用户会以为参数生效了，
     * 而实际用的是默认 agent——这种「敲了没反应」比一条用法错误难排查得多。
     * <p>
     * 三种模式里只有 CLI 不能交互，因此「新会话的初始值」只能靠参数给定；TUI 有 {@code /agent} 等命令，
     * Server 由 {@code POST /sessions} 的请求体承担，两侧都不缺能力，参数才是多余的。
     *
     * @param mode     启动模式
     * @param provided 是否提供了该参数
     * @param flag     旗标名，用于错误信息
     * @param advice   替代做法，用于错误信息
     * @throws JellyfishException 非 CLI 模式下提供了该参数时抛出
     */
    private static void requireCliOnly(StartupOptions.Mode mode, boolean provided, String flag, String advice) {
        if (mode != StartupOptions.Mode.CLI && provided) {
            throw new JellyfishException(flag + " 只在 -cli 下被接受（" + mode.getFlag() + "）：" + advice);
        }
    }

    /**
     * 保证启动模式只指定一次。
     *
     * @param mode 已解析出的模式，可为 {@code null}
     * @param flag 本次遇到的模式旗标
     * @return 模式
     * @throws JellyfishException 模式已指定过时抛出
     */
    private static StartupOptions.Mode requireSingleMode(StartupOptions.Mode mode, String flag) {
        if (mode != null) {
            throw new JellyfishException("启动模式只能指定一次：" + mode.getFlag() + " 与 " + flag);
        }
        if (FLAG_CLI.equals(flag)) {
            return StartupOptions.Mode.CLI;
        }
        return FLAG_TUI.equals(flag) ? StartupOptions.Mode.TUI : StartupOptions.Mode.SERVER;
    }

    /**
     * 校验取值非空白。
     *
     * @param flag  旗标名，用于错误信息
     * @param value 取值
     * @return 取值本身
     * @throws JellyfishException 取值为空白时抛出
     */
    private static String requireNonBlank(String flag, String value) {
        if (value.trim().isEmpty()) {
            throw new JellyfishException(flag + " 的取值不能为空白");
        }
        return value;
    }

    /**
     * 拆分 {@code provider/model}。
     *
     * @param value 取值
     * @return 二元数组：{@code [provider, model]}
     * @throws JellyfishException 缺少 {@code /} 或某一侧为空时抛出
     */
    private static String[] splitModel(String value) {
        int slash = value.indexOf('/');
        if (slash <= 0 || slash == value.length() - 1 || value.substring(0, slash).trim().isEmpty()
                || value.substring(slash + 1).trim().isEmpty()) {
            throw new JellyfishException(FLAG_MODEL + " 必须写成 provider/模型：" + value
                    + "（可用 /model 命令查看可用模型）");
        }
        return new String[] {value.substring(0, slash).trim(), value.substring(slash + 1).trim()};
    }

    /**
     * 解析端口。
     *
     * @param flag  旗标名，用于错误信息
     * @param value 取值
     * @return 端口
     * @throws JellyfishException 取值非数字或越界时抛出
     */
    private static int parsePort(String flag, String value) {
        int port;
        try {
            port = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new JellyfishException(flag + " 必须是数字：" + value);
        }
        if (port < 1 || port > MAX_PORT) {
            throw new JellyfishException(flag + " 必须在 1～" + MAX_PORT + " 之间：" + port);
        }
        return port;
    }

    /**
     * 生成未知参数的错误信息。
     *
     * @param arg 未知参数
     * @return 错误信息
     */
    private static String unknownArgumentMessage(String arg) {
        if ("-v".equals(arg)) {
            // 常见误输入：版本号是大写 V，详细日志是 --verbose
            return "未知参数：-v（版本号请用 -V，详细日志请用 --verbose）";
        }
        return "未知参数：" + arg + "（用 -h 查看用法）";
    }

    /**
     * 参数游标：把「取下一个取值」与「越界报错」收在一处，避免每个取值选项重复判断下标。
     *
     * @author zcd
     */
    private static final class Cursor {

        /** 原始参数。 */
        private final String[] args;

        /** 当前下标。 */
        private int index;

        /**
         * 构造游标。
         *
         * @param args 原始参数，不可为 {@code null}
         */
        private Cursor(String[] args) {
            this.args = args;
        }

        /**
         * 判断是否还有未消费的参数。
         *
         * @return 还有返回 {@code true}
         */
        private boolean hasNext() {
            return index < args.length;
        }

        /**
         * 消费下一个参数。
         *
         * @return 参数原文
         */
        private String next() {
            return args[index++];
        }

        /**
         * 获取当前已消费的参数个数，用于定位出错位置。
         *
         * @return 已消费个数
         */
        private int position() {
            return index;
        }

        /**
         * 消费下一个参数作为取值。
         * <p>
         * 空串是合法取值（例如 {@code -p ""}，是否为空由调用方判定），只有「没有下一个参数」才算缺值。
         *
         * @param flag 旗标名，用于错误信息
         * @return 取值
         * @throws JellyfishException 没有下一个参数时抛出
         */
        private String requireValue(String flag) {
            if (index >= args.length) {
                throw new JellyfishException(flag + " 缺少取值");
            }
            return args[index++];
        }
    }
}
