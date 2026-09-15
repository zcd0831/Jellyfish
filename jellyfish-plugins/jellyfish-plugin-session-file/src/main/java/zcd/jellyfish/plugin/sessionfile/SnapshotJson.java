package zcd.jellyfish.plugin.sessionfile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.SessionSnapshot;

import java.io.IOException;

/**
 * 会话快照的 JSON 读写。
 * <p>
 * <b>为什么要自己造一个 ObjectMapper</b>：内核的 {@code ObjectMapperWrapper} 在 {@code jellyfish-infra}，
 * 插件只能看到 {@code jellyfish-api}，因此本插件自带 Jackson（由 shade 打进插件包）。
 * <p>
 * <b>为什么注册 {@code ParameterNamesModule} 且依赖 {@code -parameters}</b>：快照类型是
 * 「全字段构造器 + 无 setter」的不可变对象，Jackson 没有可用的默认构造器，只能靠构造器参数名反序列化；
 * 参数名要在编译期用 {@code -parameters} 保留下来，属性名才与 JSON 字段对得上。
 * <p>
 * <b>未知字段不报错</b>：快照类型将来加字段时，旧文件仍然要能读出来——读不出来的代价是整段会话丢失，
 * 而少一个字段最多是某个新属性回到默认值。
 * <p>
 * 无状态（{@code ObjectMapper} 本身线程安全），可安全复用。
 *
 * @author zcd
 */
final class SnapshotJson {

    /** 共享的映射器，配置一次后只读使用。 */
    private static final ObjectMapper MAPPER = createMapper();

    /**
     * 工具类，禁止实例化。
     */
    private SnapshotJson() {
    }

    /**
     * 把会话快照序列化成便于阅读与 diff 的 JSON。
     *
     * @param snapshot 会话快照，不可为 {@code null}
     * @return JSON 文本
     * @throws JellyfishException 序列化失败时抛出
     */
    static String write(SessionSnapshot snapshot) {
        try {
            return MAPPER.writeValueAsString(snapshot);
        } catch (IOException e) {
            throw new JellyfishException("会话快照序列化失败: " + snapshot.getSessionId(), e);
        }
    }

    /**
     * 把 JSON 还原成会话快照。
     *
     * @param json JSON 文本
     * @param source 来源描述（文件路径），仅用于异常信息
     * @return 会话快照，保证非 {@code null}
     * @throws JellyfishException JSON 非法或缺少必需字段时抛出
     */
    static SessionSnapshot read(String json, String source) {
        try {
            SessionSnapshot snapshot = MAPPER.readValue(json, SessionSnapshot.class);
            if (snapshot == null) {
                throw new JellyfishException("会话快照为空: " + source);
            }
            return snapshot;
        } catch (JsonProcessingException e) {
            throw new JellyfishException("会话快照解析失败: " + source + " (" + e.getOriginalMessage() + ')', e);
        } catch (IOException e) {
            throw new JellyfishException("会话快照读取失败: " + source + " (" + e.getMessage() + ')', e);
        }
    }

    /**
     * 构造映射器。
     *
     * @return 已配置的映射器
     */
    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new ParameterNamesModule());
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // 缩进输出：文件是要被人读、被 git diff 的
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        return mapper;
    }
}
