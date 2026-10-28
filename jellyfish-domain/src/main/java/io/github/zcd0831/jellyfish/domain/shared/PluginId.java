package io.github.zcd0831.jellyfish.domain.shared;

import lombok.Value;

/** 插件标识 = `plugins/<id>/` 的目录名，必须与 jar 内 `Plugin-Id` 一致。 */
@Value
public class PluginId {

    String value;

    public static PluginId of(String value) {
        return new PluginId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
