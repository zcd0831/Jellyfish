package zcd.jellyfish.server.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code POST /sessions} 的请求体：三个字段全可选。
 * <p>
 * <b>为什么全是可空</b>：不填表示「跟随默认」——{@code agentId} / {@code model} 由内核按默认 agent 与
 * 默认模型解析。服务端不再另有一套启动参数默认值： 「服务级默认」归配置文件（模型默认值在
 * {@code models.json}），单次覆盖归本请求体。
 * <p>
 * <b>权限模式一类的「模式」不在这里</b>：内核不持有「有哪些模式」的知识，模式是插件能力。
 * 需要新建会话就处于某个模式时，建完会话后走插件提供的命令（例如 {@code /plan on}）。
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

    /**
     * 构造请求。
     *
     * @param agentId  agent 标识，可为 {@code null}
     * @param provider provider 名，可为 {@code null}
     * @param model    模型名，可为 {@code null}
     */
    @JsonCreator
    public CreateSessionRequest(@JsonProperty("agentId") String agentId,
                                @JsonProperty("provider") String provider,
                                @JsonProperty("model") String model) {
        this.agentId = agentId;
        this.provider = provider;
        this.model = model;
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
}
