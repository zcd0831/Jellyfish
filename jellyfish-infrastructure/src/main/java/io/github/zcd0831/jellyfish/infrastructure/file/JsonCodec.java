package io.github.zcd0831.jellyfish.infrastructure.file;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 文件即数据库，JSON 就是存储格式。
 */
public final class JsonCodec {

    private final ObjectMapper mapper;

    public JsonCodec() {
        this.mapper = new ObjectMapper()
                .registerModule(new IdJsonModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, true);
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("serialize failed: " + value.getClass().getName(), e);
        }
    }

    public <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("deserialize failed: " + type.getName(), e);
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
