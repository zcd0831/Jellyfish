package io.github.zcd0831.jellyfish.infrastructure.file;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * Id 值对象与字符串互转；领域层不引 Jackson，序列化知识留在本层。
 */
final class IdJsonModule extends SimpleModule {

    private static final List<String> ID_TYPES = Arrays.asList(
            "SessionId", "PlanId", "StepId", "RecordId", "ExperienceId", "ReflectionId",
            "VerificationReportId", "AgentId", "ModelId", "ToolId", "SkillId", "ProviderId", "BudgetId");

    @SuppressWarnings({"unchecked", "rawtypes"})
    IdJsonModule() {
        super("jellyfish-ids");
        for (String simpleName : ID_TYPES) {
            Class type = load(simpleName);
            addSerializer(type, new JsonSerializer() {
                @Override
                public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                    gen.writeString(value.toString());
                }
            });
            addDeserializer(type, new JsonDeserializer() {
                @Override
                public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                    return of(type, p.getValueAsString());
                }
            });
        }
    }

    private static Class<?> load(String simpleName) {
        try {
            return Class.forName("io.github.zcd0831.jellyfish.domain.shared." + simpleName);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object of(Class<?> type, String value) {
        try {
            Method m = type.getMethod("of", String.class);
            return m.invoke(null, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
