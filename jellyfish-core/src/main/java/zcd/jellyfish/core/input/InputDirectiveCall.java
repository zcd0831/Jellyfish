package zcd.jellyfish.core.input;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条已解析的输入指令：哪个标记认领了它、要调用哪个工具、参数是什么。
 * <p>
 * <b>为什么把「解析」与「执行」拆成两步</b>：外壳需要在提交执行之前先重置界面上的「运行中」暂存区，
 * 否则执行线程可能在重置之前就写出第一段实时输出，重置会把那段输出抹掉——一个只在慢机器上偶发的
 * 竞态。因此解析（同步、纯函数）先返回本对象，外壳做完自己的准备再调
 * {@link InputDirectives#start}。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class InputDirectiveCall {

    /** 认领本次输入的标记字符。 */
    private final String marker;

    /** 用户输入原文（已修剪），用于回显。 */
    private final String input;

    /** 要调用的工具名（路由键）。 */
    private final String toolName;

    /** 工具参数，保证非 {@code null}。 */
    private final Map<String, Object> arguments;

    /**
     * 构造已解析的指令。
     *
     * @param marker    标记字符，不可为空白
     * @param input     用户输入原文，可为 {@code null}
     * @param toolName  工具名，不可为空白
     * @param arguments 工具参数，可为 {@code null}
     */
    InputDirectiveCall(String marker, String input, String toolName, Map<String, Object> arguments) {
        this.marker = marker;
        this.input = input == null ? "" : input;
        this.toolName = toolName;
        this.arguments = arguments == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(arguments));
    }

    /**
     * 获取标记字符。
     *
     * @return 标记字符，保证非 {@code null}
     */
    public String getMarker() {
        return marker;
    }

    /**
     * 获取用户输入原文（已修剪）。
     *
     * @return 输入原文，保证非 {@code null}
     */
    public String getInput() {
        return input;
    }

    /**
     * 获取工具名。
     *
     * @return 工具名，保证非 {@code null}
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * 获取工具参数。
     *
     * @return 不可变参数映射，保证非 {@code null}
     */
    public Map<String, Object> getArguments() {
        return arguments;
    }

    @Override
    public String toString() {
        return "InputDirectiveCall{marker=" + marker + ", toolName=" + toolName + '}';
    }
}
