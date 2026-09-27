package zcd.jellyfish.server.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code POST /sessions} 的请求体：四个字段全可选。
 * <p>
 * <b>为什么全是可空</b>：不填表示「跟随默认」——{@code agentId} / {@code model} 由内核按默认 agent 与
 * 默认模型解析，{@code permissionMode} 按 {@code NORMAL}。服务端启动参数 {@code --agent/--model/--mode}
 * 作为这里的缺省值，因此「服务级默认 + 单次覆盖」在同一处表达。
 * <p>
 * <b>为什么 {@code permissionMode} 是字符串而不是枚举</b>：非法取值要给一条可读的中文提示
 * （「权限模式只能是 NORMAL 或 PLAN」），而直接绑枚举会先被 Jackson 抛成一句泛化错误。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class CreateSessionRequest {

    /** 绑定的 agent 标识，可为 {@code null}。 */
    private final String agentId;

    /** provider 名，可为 {@code null}。 */
    private final String provider;

    /** 模型名，可为 {@code null}。 */
    private final String model;

    /** 权限模式名（{@code NORMAL} / {@code PLAN}），可为 {@code null}。 */
    private final String permissionMode;

    /**
     * 构造请求。
     *
     * @param agentId        agent 标识，可为 {@code null}
     * @param provider       provider 名，可为 {@code null}
     * @param model          模型名，可为 {@code null}
     * @param permissionMode 权限模式名，可为 {@code null}
     */
    @JsonCreator
    public CreateSessionRequest(@JsonProperty("agentId") String agentId,
                                @JsonProperty("provider") String provider,
                                @JsonProperty("model") String model,
                                @JsonProperty("permissionMode") String permissionMode) {
        this.agentId = agentId;
        this.provider = provider;
        this.model = model;
        this.permissionMode = permissionMode;
    }

    /**
     * 获取 agent 标识。
     *
     * @return agent 标识，可能为 {@code null}
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * 获取 provider 名。
     *
     * @return provider 名，可能为 {@code null}
     */
    public String getProvider() {
        return provider;
    }

    /**
     * 获取模型名。
     *
     * @return 模型名，可能为 {@code null}
     */
    public String getModel() {
        return model;
    }

    /**
     * 获取权限模式名。
     *
     * @return 权限模式名，可能为 {@code null}
     */
    public String getPermissionMode() {
        return permissionMode;
    }
}
