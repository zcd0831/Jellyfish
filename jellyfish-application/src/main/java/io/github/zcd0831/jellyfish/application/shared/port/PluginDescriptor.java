package io.github.zcd0831.jellyfish.application.shared.port;

import io.github.zcd0831.jellyfish.domain.shared.PluginId;

/**
 * 运行时读到的插件事实。`loaded` 是进程内事实，不落盘。
 * 规则出处：05-action §5.6。
 */
public final class PluginDescriptor {

    private final PluginId id;
    private final String version;
    private final int apiVersion;
    private final boolean loaded;

    public PluginDescriptor(PluginId id, String version, int apiVersion, boolean loaded) {
        this.id = id;
        this.version = version;
        this.apiVersion = apiVersion;
        this.loaded = loaded;
    }

    public PluginId id() {
        return id;
    }

    public String version() {
        return version;
    }

    /** 插件声明的宿主 API 版本（来自 `Plugin-Requires`）。 */
    public int apiVersion() {
        return apiVersion;
    }

    public boolean loaded() {
        return loaded;
    }
}
