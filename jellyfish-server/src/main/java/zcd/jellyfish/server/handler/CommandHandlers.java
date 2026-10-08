package zcd.jellyfish.server.handler;

import io.undertow.server.HttpServerExchange;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CommandArguments;
import zcd.jellyfish.api.extension.CommandChoice;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.infra.command.CommandInfo;
import zcd.jellyfish.infra.command.CommandManager;
import zcd.jellyfish.infra.session.SessionManager;
import zcd.jellyfish.server.ServerConfig;
import zcd.jellyfish.server.dto.ChoiceDto;
import zcd.jellyfish.server.dto.CommandExecRequest;
import zcd.jellyfish.server.dto.CommandInfoDto;
import zcd.jellyfish.server.dto.CommandResultDto;
import zcd.jellyfish.server.http.ApiException;
import zcd.jellyfish.server.http.JsonBody;
import zcd.jellyfish.server.http.PathParams;
import zcd.jellyfish.server.http.Responses;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * 命令端点的处理器：{@code GET /commands}、{@code GET /commands/{name}/options}、
 * {@code POST /sessions/{id}/commands}。
 * <p>
 * <b>命令域一处不复制</b>：三态判定、别名解析、候选查询全部委托 {@link CommandManager}——
 * 它是内核里唯一的命令入口，本处理器只做「HTTP 形状 ↔ 命令域形状」的翻译。
 * <p>
 * <b>为什么 {@code UNKNOWN} 回 404 而不是 200</b>：{@code ERROR}（命令存在但这次没成）与
 * {@code UNKNOWN}（没有这条命令）对调用方是两件事，塞进同一个状态码会让前端只能靠解析文本区分。
 * 两者都返回同一份 {@link CommandResultDto}，机器可读的判别字段是 {@code kind}；
 * 404 只是让「路径级不存在」也能被通用 HTTP 客户端正确分类。
 * <p>
 * <b>为什么 {@code input} 不以 {@code /} 开头就报 400</b>：这是接口层面的分流约定——命令走本端点，
 * 对话走 {@code /chat}。把一段自然语言悄悄按命令处理（或反过来）会让调用方拿到莫名其妙的
 * {@code UNKNOWN} 文案。
 * <p>
 * <b>「命令接力」在本端点上只能走半程</b>：命令返回 {@code CommandResult.handoff(...)} 时，
 * 结果体里的 {@code handoff} 字段即「请接着把这段文本发去对话」的说明。
 * TUI / CLI 上 {@code ConversationService} 会在同一次提交里完成接力（换掉输入、起回合），
 * 而本端点是纯 JSON 返回、与 {@code /chat} 的 SSE 契约分开，因此起回合这一步必须由客户端补：
 * 拿 {@code handoff} 去 {@code POST /sessions/{id}/chat}。本类刻意不在这里自己起回合——
 * 那会把两个端点的响应形状混成一种，客户端从此必须靠猜。
 * <p>
 * 无状态（只持有协作者），可安全跨线程调用。
 *
 * @author zcd
 */
public final class CommandHandlers {

    /** 命令域服务。 */
    private final CommandManager commands;

    /** 会话域服务：命令按会话寻址，先校验会话存在。 */
    private final SessionManager sessions;

    /** 运行参数（请求体上限）。 */
    private final ServerConfig config;

    /**
     * 构造处理器。
     *
     * @param commands 命令域服务，不可为 {@code null}
     * @param sessions 会话域服务，不可为 {@code null}
     * @param config   运行参数，不可为 {@code null}
     */
    public CommandHandlers(CommandManager commands, SessionManager sessions, ServerConfig config) {
        this.commands = commands;
        this.sessions = sessions;
        this.config = config;
    }

    /**
     * 处理 {@code GET /commands}：结构化命令清单。
     *
     * @param exchange HTTP 交换对象
     * @param params   路径参数（本端点不使用）
     */
    public void list(HttpServerExchange exchange, PathParams params) {
        List<CommandInfoDto> items = new ArrayList<CommandInfoDto>();
        for (CommandInfo info : commands.commands()) {
            items.add(CommandInfoDto.of(info));
        }
        Responses.writeJson(exchange, Responses.OK, items);
    }

    /**
     * 处理 {@code GET /commands/{name}/options}：候选值。
     *
     * @param exchange HTTP 交换对象
     * @param params   路径参数（含 {@code name}）
     */
    public void options(HttpServerExchange exchange, PathParams params) {
        String sessionId = queryParam(exchange, "sessionId");
        List<ChoiceDto> items = new ArrayList<ChoiceDto>();
        for (CommandChoice choice : commands.options(params.get("name"), sessionId)) {
            items.add(ChoiceDto.of(choice));
        }
        Responses.writeJson(exchange, Responses.OK, items);
    }

    /**
     * 处理 {@code POST /sessions/{id}/commands}：执行一条命令。
     *
     * @param exchange HTTP 交换对象
     * @param params   路径参数（含 {@code id}）
     */
    public void execute(HttpServerExchange exchange, PathParams params) {
        String sessionId = requireSession(params.get("id"));
        CommandExecRequest request = JsonBody.read(exchange, CommandExecRequest.class, config.getMaxBodyBytes());
        if (request == null) {
            throw new ApiException(Responses.BAD_REQUEST, Responses.CODE_BAD_REQUEST,
                    "请求体不能为空（input 或 name 二选一）");
        }
        boolean hasInput = notBlank(request.getInput());
        boolean hasName = notBlank(request.getName());
        if (hasInput == hasName) {
            throw new ApiException(Responses.BAD_REQUEST, Responses.CODE_BAD_REQUEST,
                    "input 与 name 必须二选一");
        }
        CommandResult result = hasInput
                ? executeByInput(request.getInput(), sessionId)
                : executeByName(request.getName(), request.getArgs(), sessionId);
        // UNKNOWN 用 404 表达「没有这条命令」，并仍返回结果体：判别字段是 kind
        int status = result.getKind() == CommandResult.Kind.UNKNOWN ? Responses.NOT_FOUND : Responses.OK;
        Responses.writeJson(exchange, status, CommandResultDto.of(result));
    }

    /**
     * 校验会话存在，返回其标识。
     * <p>
     * <b>为什么必须校验</b>：不校验的话，一个不存在的会话 id 也能让命令跑起来，接口回
     * {@code 200 + kind=ERROR}——调用方无法区分「会话不存在」与「命令这次失败」，
     * 而 {@code /chat} 与 {@code GET /sessions/{id}} 对同一种输入都回 404。
     *
     * @param sessionId 路径里的会话标识，可为 {@code null}
     * @return 已确认存在的会话标识，保证非空白
     * @throws ApiException 会话不存在时抛出 404
     */
    private String requireSession(String sessionId) {
        return SessionPath.require(sessions, sessionId);
    }

    /**
     * 以原文入口执行。
     *
     * @param input     命令原文
     * @param sessionId 会话标识，可为 {@code null}
     * @return 命令结果
     * @throws ApiException 原文不是命令时抛出
     */
    private CommandResult executeByInput(String input, String sessionId) {
        if (!commands.isCommand(input)) {
            throw new ApiException(Responses.BAD_REQUEST, "NOT_A_COMMAND",
                    "输入不是命令（应以 / 开头）：" + input + "（对话请用 POST /sessions/{id}/chat）");
        }
        ServerCommandGate.check(input);
        return commands.execute(input, sessionId);
    }

    /**
     * 以结构化入口执行。
     *
     * @param name      命令名或别名
     * @param args      参数，可为 {@code null}
     * @param sessionId 会话标识，可为 {@code null}
     * @return 命令结果
     */
    private CommandResult executeByName(String name, List<String> args, String sessionId) {
        ServerCommandGate.check("/" + name);
        List<String> tokens = args == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(args));
        CommandArguments arguments = new CommandArguments(tokens, String.join(" ", tokens));
        return commands.execute(name, arguments, sessionId);
    }

    /**
     * 读取查询参数。
     *
     * @param exchange HTTP 交换对象
     * @param name     参数名
     * @return 第一个取值；不存在时返回 {@code null}
     */
    private static String queryParam(HttpServerExchange exchange, String name) {
        Map<String, Deque<String>> params = exchange.getQueryParameters();
        Deque<String> values = params == null ? null : params.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    /**
     * 判断字符串非空白。
     *
     * @param value 字符串，可为 {@code null}
     * @return 非空白返回 {@code true}
     */
    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
