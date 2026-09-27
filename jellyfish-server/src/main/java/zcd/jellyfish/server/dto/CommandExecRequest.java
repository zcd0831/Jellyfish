package zcd.jellyfish.server.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * {@code POST /sessions/{id}/commands} 的请求体：原文入口与结构化入口二选一。
 * <p>
 * <b>为什么要两条入口</b>：输入框里拿到的是 {@code "/agent coder"} 这样一段原文，而菜单式前端手里是
 * 「命令名 + 参数数组」。两种形态都映射到 {@link zcd.jellyfish.infra.command.CommandManager}
 * 的同一条分发路径，因此这里必须能分别表达，但**不能同时给**——同时给意味着前端对「以哪个为准」
 * 有歧义，处理器一律判 400。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class CommandExecRequest {

    /** 命令原文（如 {@code "/agent coder"}），可为 {@code null}。 */
    private final String input;

    /** 命令名或别名，可为 {@code null}。 */
    private final String name;

    /** 命令参数，可为 {@code null}。 */
    private final List<String> args;

    /**
     * 构造请求。
     *
     * @param input 命令原文，可为 {@code null}
     * @param name  命令名或别名，可为 {@code null}
     * @param args  命令参数，可为 {@code null}
     */
    @JsonCreator
    public CommandExecRequest(@JsonProperty("input") String input,
                              @JsonProperty("name") String name,
                              @JsonProperty("args") List<String> args) {
        this.input = input;
        this.name = name;
        this.args = args;
    }

    /**
     * 获取命令原文。
     *
     * @return 命令原文，可能为 {@code null}
     */
    public String getInput() {
        return input;
    }

    /**
     * 获取命令名。
     *
     * @return 命令名，可能为 {@code null}
     */
    public String getName() {
        return name;
    }

    /**
     * 获取命令参数。
     *
     * @return 参数列表，可能为 {@code null}
     */
    public List<String> getArgs() {
        return args;
    }
}
