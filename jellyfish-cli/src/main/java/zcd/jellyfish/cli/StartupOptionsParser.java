package zcd.jellyfish.cli;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.PermissionMode;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动参数解析器：把 {@code String[] args} 解析成 {@link StartupOptions}。
 * <p>
 * <b>为什么手写</b>：参数表只有十几个，且都是简单取值 / 开关；为此引入命令行框架收益很低，
 * 反而给 JDK 1.8 的构建引入新依赖。手写解析无状态、无 IO，可独立单测。
 * <p>
 * <b>为什么「不猜模式」</b>：没有模式旗标就是用法错误，刻意不做「默认 -cli」——那会让
 * 单独一条 {@code jellyfish} 卡在「等 stdin EOF」上，比直接报错更难排查。
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

    /** 权限模式旗标。 */
    private static final String FLAG_PERMISSION_MODE = "--mode";

    /** 端口旗标。 */
    private static final String FLAG_PORT = "--port";

    /** 绑定地址旗标。 */
    private static final String FLAG_HOST = "--host";

    /** 思考过程旗标。 */
    private static final String FLAG_SHOW_THINKING = "--show-thinking";

    /** 详细日志旗标。 */
    private static final String FLAG_VERBOSE = "--verbose";

    /** 权限模式取值：计划模式。 */
    private static final String VALUE_PLAN = "plan";

    /** 权限模式取值：常规模式。 */
    private static final String VALUE_NORMAL = "normal";

    /** 端口上界。 */
    private static final int MAX_PORT = 65535;

    /** 用法文本。 */
    private static final String USAGE = ""
            + "用法：jellyfish <模式> [选项]\n"
            + "\n"
            + "模式（三选一，必填）：\n"
            + "  -cli                  单次调用、不交互：进一个输入，出一次结果后退出\n"
            + "  -tui                  交互式终端界面（尚未实现）\n"
            + "  -server [端口]        以 HTTP 服务运行（尚未实现），端口缺省 "
            + StartupOptions.DEFAULT_PORT + "\n"
            + "\n"
            + "选项：\n"
            + "  -p, --print <输入>      单次模式的输入；缺省时从 stdin 读到 EOF\n"
            + "      --session <会话>    切换到已有会话\n"
            + "      --agent <agentId>   新建会话时绑定 agent\n"
            + "      --model <provider/模型>\n"
            + "                          新建会话时指定模型（必须含 \"/\"）\n"
            + "      --mode <plan|normal>\n"
            + "                          新建会话的权限模式\n"
            + "      --port <端口>       服务器端口（等价于 -server 的位置参数）\n"
            + "      --host <地址>       服务器绑定地址，缺省 " + StartupOptions.DEFAULT_HOST + "\n"
            + "      --show-thinking     把思考过程打到 stderr\n"
            + "      --verbose           日志级别降到 DEBUG\n"
            + "  -h, --help              显示本帮助\n"
            + "  -V, --version           显示版本号\n"
            + "\n"
            + "输出约定：回答与命令结果走 stdout，诊断、工具进度与日志走 stderr。\n"
            + "退出码：0 成功，2 用法错误，3 启动失败，4 运行失败，5 模式未实现，6 回合未收敛。";

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
     *
     * @param args 命令行参数，可为 {@code null}
     * @return 启动参数，保证非 {@code null}
     * @throws JellyfishException 参数缺失、未知、重复或取值非法时抛出
     */
    public static StartupOptions parse(String[] args) {
        Cursor cursor = new Cursor(args == null ? new String[0] : args);
        StartupOptions.Mode mode = null;
        String prompt = null;
        String sessionId = null;
        String agentId = null;
        String provider = null;
        String model = null;
        PermissionMode permissionMode = null;
        Integer portOption = null;
        String hostOption = null;
        boolean showThinking = false;
        boolean verbose = false;
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
            } else if (FLAG_VERBOSE.equals(arg)) {
                verbose = true;
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
            } else if (FLAG_PERMISSION_MODE.equals(arg)) {
                permissionMode = parsePermissionMode(cursor.requireValue(arg));
            } else if (FLAG_PORT.equals(arg)) {
                portOption = parsePort(FLAG_PORT, cursor.requireValue(arg));
            } else if (FLAG_HOST.equals(arg)) {
                hostOption = requireNonBlank(arg, cursor.requireValue(arg));
            } else if (arg.startsWith("--") && arg.indexOf('=') > 0) {
                throw new JellyfishException("不支持 --key=value 写法：" + arg + "（请写成 --key value）");
            } else if (arg.startsWith("-") && !"-".equals(arg)) {
                throw new JellyfishException(unknownArgumentMessage(arg));
            } else {
                positionals.add(arg);
            }
        }
        return build(mode, prompt, sessionId, agentId, provider, model, permissionMode, portOption, hostOption,
                showThinking, verbose, help, version, positionals);
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
     * @param permissionMode 权限模式，可为 {@code null}
     * @param portOption     {@code --port} 取值，可为 {@code null}
     * @param hostOption     {@code --host} 取值，可为 {@code null}
     * @param showThinking   是否显示思考过程
     * @param verbose        是否详细日志
     * @param help           是否请求帮助
     * @param version        是否请求版本号
     * @param positionals    位置参数列表
     * @return 启动参数
     * @throws JellyfishException 参数组合非法时抛出
     */
    private static StartupOptions build(StartupOptions.Mode mode, String prompt, String sessionId, String agentId,
                                        String provider, String model, PermissionMode permissionMode,
                                        Integer portOption, String hostOption, boolean showThinking, boolean verbose,
                                        boolean help, boolean version, List<String> positionals) {
        if (help || version) {
            // 帮助与版本不执行任何模式：模式只用于填一个合法值，避免为一个纯展示请求纠结「模式没给」
            StartupOptions.Mode displayMode = mode == null ? StartupOptions.Mode.CLI : mode;
            return StartupOptions.builder(displayMode)
                    .prompt(prompt).sessionId(sessionId).agentId(agentId).model(provider, model)
                    .permissionMode(permissionMode).port(StartupOptions.DEFAULT_PORT).host(hostOption)
                    .showThinking(showThinking).verbose(verbose).help(help).version(version).build();
        }
        if (mode == null) {
            throw new JellyfishException("请指定启动模式：-cli / -tui / -server（用 -h 查看用法）");
        }
        if (mode != StartupOptions.Mode.SERVER && (!positionals.isEmpty() || portOption != null
                || hostOption != null)) {
            throw new JellyfishException("只有 -server 支持端口与绑定地址");
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
                .permissionMode(permissionMode).port(port).host(hostOption)
                .showThinking(showThinking).verbose(verbose).help(help).version(version).build();
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
     * 解析权限模式取值，大小写不敏感。
     *
     * @param value 取值
     * @return 权限模式
     * @throws JellyfishException 取值不是 {@code plan} / {@code normal} 时抛出
     */
    private static PermissionMode parsePermissionMode(String value) {
        if (VALUE_PLAN.equalsIgnoreCase(value)) {
            return PermissionMode.PLAN;
        }
        if (VALUE_NORMAL.equalsIgnoreCase(value)) {
            return PermissionMode.NORMAL;
        }
        throw new JellyfishException(FLAG_PERMISSION_MODE + " 只支持 plan 或 normal：" + value);
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
