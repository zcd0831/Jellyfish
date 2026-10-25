package io.github.zcd0831.jellyfish.application.action.port;

import io.github.zcd0831.jellyfish.domain.action.plugin.PluginManifest;
import io.github.zcd0831.jellyfish.domain.shared.PluginId;

import java.util.List;

/**
 * 插件授权意图的仓储：`plugins/<id>/plugin.json`。
 * 卸载 = 删目录（与"删 skills/ 目录即淘汰"同构），所以没有 delete。
 * 规则出处：模块-应用层 §6、模块-行动 §7.6。
 */
public interface PluginRepository {

    List<PluginManifest> list();

    PluginManifest load(PluginId pluginId);

    void save(PluginManifest manifest);
}
