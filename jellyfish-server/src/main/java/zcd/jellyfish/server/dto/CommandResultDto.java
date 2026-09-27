package zcd.jellyfish.server.dto;

import zcd.jellyfish.api.extension.CommandChoice;
import zcd.jellyfish.api.extension.CommandResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 命令执行结果：{@code POST /sessions/{id}/commands} 的返回体。
 * <p>
 * <b>{@code kind} 为什么是字符串而不是枚举</b>：它是 HTTP 合同的一部分，前端拿到的是
 * {@code "OK"} / {@code "ERROR"} / {@code "UNKNOWN"} 三个字面量；用字符串让合同与内核枚举的变化解耦
 * （枚举改个名字不该改接口）。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class CommandResultDto {

    /** 三态字面量：已执行。 */
    public static final String KIND_OK = "OK";

    /** 三态字面量：命令存在但执行失败。 */
    public static final String KIND_ERROR = "ERROR";

    /** 三态字面量：没有这条命令。 */
    public static final String KIND_UNKNOWN = "UNKNOWN";

    /** 结果状态字面量。 */
    private final String kind;

    /** 可渲染文本，可为 {@code null}。 */
    private final String output;

    /** 候选值清单，保证非 {@code null}。 */
    private final List<ChoiceDto> choices;

    /**
     * 构造结果。
     *
     * @param kind    结果状态字面量
     * @param output  可渲染文本，可为 {@code null}
     * @param choices 候选值清单，可为 {@code null}
     */
    public CommandResultDto(String kind, String output, List<ChoiceDto> choices) {
        this.kind = kind;
        this.output = output;
        this.choices = choices == null
                ? Collections.<ChoiceDto>emptyList()
                : Collections.unmodifiableList(new ArrayList<ChoiceDto>(choices));
    }

    /**
     * 把 api 命令结果投影成 DTO。
     *
     * @param result api 命令结果，不可为 {@code null}
     * @return 结果 DTO
     */
    public static CommandResultDto of(CommandResult result) {
        List<ChoiceDto> choices = new ArrayList<ChoiceDto>();
        for (CommandChoice choice : result.getChoices()) {
            choices.add(ChoiceDto.of(choice));
        }
        return new CommandResultDto(result.getKind().name(), result.getOutput(), choices);
    }

    /**
     * 获取结果状态字面量。
     *
     * @return 状态字面量
     */
    public String getKind() {
        return kind;
    }

    /**
     * 获取可渲染文本。
     *
     * @return 文本，可能为 {@code null}
     */
    public String getOutput() {
        return output;
    }

    /**
     * 获取候选值清单。
     *
     * @return 候选清单，保证非 {@code null}
     */
    public List<ChoiceDto> getChoices() {
        return choices;
    }
}
