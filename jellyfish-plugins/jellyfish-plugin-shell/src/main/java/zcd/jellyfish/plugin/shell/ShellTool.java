package zcd.jellyfish.plugin.shell;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.extension.ToolOutputSink;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code shell} 工具：执行一条命令，把输出交给内核的捕获通道。
 * <p>
 * <b>输入是命令原文，不是 argv 数组</b>：本工具执行的是 {@code /bin/sh -c "<原文>"}，
 * 因此管道、重定向、通配符、{@code &&} 都按 shell 语义工作。拆成数组则要自己实现整门 shell 语法，
 * 而且会与用户对「命令行」的预期不一致。
 * <p>
 * <b>没有沙箱</b>：命令以本进程的权限执行，能读写本用户的任何文件。它是能力而不是漏洞，
 * 但必须让用户知道——推荐把它放进 {@code askTools} 让人过一眼。
 * <p>
 * <b>输出为什么走 sink 而不是自己拼字符串</b>：命令行的输出是无界且不可再取的（{@code yes} 一分钟
 * 能产出几个 GB），只有内核能在输出产生的过程中接管：内存有界、超限落盘、回灌头尾预览。
 * 本工具因此不物化输出、也不知道落盘路径。
 * <p>
 * 无状态（配置与执行器都是注入的只读协作者），可安全复用。
 *
 * @author zcd
 */
final class ShellTool implements ExtensionHandler<ToolCallRequest, ToolCallResult> {

    /** 工具名，也是路由键。 */
    static final String TOOL_NAME = "shell";

    /** 进程工作目录，相对路径的解析基准。 */
    private static final Path WORKING_DIRECTORY = Paths.get("").toAbsolutePath().normalize();

    /** 工具名片，无状态因此整个插件共用一个实例。 */
    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            TOOL_NAME,
            "在本机执行一条 shell 命令并返回输出（stdout 与 stderr 合并）。"
                    + "命令原文交给 /bin/sh -c 执行，因此管道、重定向、通配符都能用。"
                    + "注意：每次调用都是一个全新的 shell，cd 不会跨调用保留——需要换目录就用"
                    + " `cd 目录 && 命令` 或传 cwd 参数。"
                    + "读文件、列目录、搜索文本请优先用 read_file / list_dir / grep_files，它们更省上下文。"
                    + "不要用它执行交互式命令（如 vi、ssh、sudo）：标准输入已关闭，会立刻失败。",
            parameters(),
            Collections.singletonList("command"));

    /** 插件配置。 */
    private final PluginConfig config;

    /** 执行器。 */
    private final ShellProcessRunner runner;

    /**
     * 构造工具。
     *
     * @param config 插件配置，不可为 {@code null}
     * @param runner 执行器，不可为 {@code null}
     */
    ShellTool(PluginConfig config, ShellProcessRunner runner) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.runner = Objects.requireNonNull(runner, "runner must not be null");
    }

    /**
     * 获取工具名片。
     *
     * @return 描述符，保证非 {@code null}
     */
    static ToolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ToolCallResult handle(ToolCallRequest request) {
        ShellArguments arguments = new ShellArguments(request.getArguments());
        String command = arguments.requireCommand();
        Path workingDirectory = resolveWorkingDirectory(arguments.optionalCwd());
        ShellInvocation invocation = new ShellInvocation(command, workingDirectory,
                timeoutMillis(arguments.optionalTimeoutSeconds()),
                config.idleTimeoutSeconds() * 1000L,
                ShellEnvironment.build(System.getenv(), sensitivePatterns(), config.environment()));
        ToolOutputSink sink = request.getOutputSink();
        ShellResult result = runner.run(invocation, sink, request.getCancellationToken());
        String summary = result.summary(workingDirectory.toString());
        // 元数据先声明再收尾：它必须出现在正文首行，而正文是 sink 收尾时才渲染的
        sink.summary(summary);
        String captured = sink.finish();
        // sink 是 NOOP 的调用点（非内核调用）拿不到任何内容，此时至少要让结论可见，
        // 否则模型看到一片空白，无法区分「命令没输出」与「命令没跑」
        // 元数据与首行结论同源：模型读文本，界面与审计读字段
        return new ToolCallResult(TOOL_NAME, captured == null ? summary : captured, result.metadata());
    }

    /**
     * 解析工作目录。
     *
     * @param cwd 模型给的目录文本，可为 {@code null}
     * @return 规范化绝对路径，保证非 {@code null}
     * @throws JellyfishException 目录不存在或不是目录时抛出
     */
    private static Path resolveWorkingDirectory(String cwd) {
        if (cwd == null) {
            return WORKING_DIRECTORY;
        }
        Path resolved = Paths.get(cwd).toAbsolutePath().normalize();
        if (!Files.isDirectory(resolved)) {
            // 提前报错而不是把失败留给 shell：后者会以「命令找不到」的形式出现，
            // 而真实原因是目录写错了
            throw new JellyfishException("工作目录不存在或不是目录: " + resolved);
        }
        return resolved;
    }

    /**
     * 计算本次调用的墙钟超时（毫秒）。
     *
     * @param requestedSeconds 模型请求的秒数，{@code -1} 表示未指定
     * @return 超时毫秒数，保证大于 0
     */
    private long timeoutMillis(int requestedSeconds) {
        int seconds = requestedSeconds < 0 ? config.timeoutSeconds() : requestedSeconds;
        // 钳制而不是拒绝：模型要求一个过大的超时通常只是保守，直接报错会浪费一整轮往返
        return Math.min(seconds, config.maxTimeoutSeconds()) * 1000L;
    }

    /**
     * 取得生效的脱敏模式（内置表 + 用户追加）。
     *
     * @return 模式列表，保证非 {@code null}
     */
    private List<String> sensitivePatterns() {
        return ShellEnvironment.sensitivePatterns(config.extraSensitivePatterns());
    }

    /**
     * 构造参数 Schema。
     * <p>
     * 内核只要求这里是 JSON Schema 的 {@code properties} 部分（{@code type: object} 与
     * {@code required} 由内核自己包上），因此用普通 Map 表达，不引入 JSON 库——
     * 插件多一个依赖就多一份要 shade 进插件包的东西。
     *
     * @return 参数 Schema，保证非 {@code null}
     */
    private static Map<String, Object> parameters() {
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        parameters.put("command", type("string", "要执行的命令行原文，交给 /bin/sh -c 执行"));
        parameters.put("cwd", type("string", "工作目录，相对路径按进程工作目录解析；缺省为进程工作目录"));
        parameters.put("timeout_seconds", type("integer",
                "本次调用的超时秒数，超过则终止命令；上限由插件配置 maxTimeoutSeconds 决定"));
        return parameters;
    }

    /**
     * 构造单个参数的 Schema 片段。
     *
     * @param type        类型
     * @param description 用途说明
     * @return 片段，保证非 {@code null}
     */
    private static Map<String, Object> type(String type, String description) {
        Map<String, Object> property = new LinkedHashMap<String, Object>();
        property.put("type", type);
        property.put("description", description);
        return property;
    }

    /**
     * 供诊断使用的参数名清单。
     *
     * @return 参数名列表
     */
    static List<String> parameterNames() {
        return Collections.unmodifiableList(Arrays.asList("command", "cwd", "timeout_seconds"));
    }
}
