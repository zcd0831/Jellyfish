package io.github.zcd0831.jellyfish.application.memory.port;

import io.github.zcd0831.jellyfish.domain.memory.reflection.Reflection;
import io.github.zcd0831.jellyfish.domain.shared.ReflectionId;
import java.util.Optional;

/**
 * 不可变反思。规则出处：模块-基础设施层 §3。
 */
public interface ReflectionRepository {

    void save(Reflection reflection);

    Optional<Reflection> get(ReflectionId id);
}
