package io.github.zcd0831.jellyfish.shell.tui;

import io.github.zcd0831.jellyfish.application.action.ManagePluginsUseCase;
import io.github.zcd0831.jellyfish.domain.shared.PluginId;

/**
 * `/plugins` 的四个运行期动作：装 / 授权 / 收回 / 卸载。
 * 「装」与「授权」分开——装只落自己的 plugin.json，授权才让它进入世界（08-application §8.9.6）；
 * 卸载只是摘掉运行期，目录由用户自己删（05-action §5.8.6 规则 1）。
 * 卸载要先确认：摘掉运行期之后插件就没了，要再装得重新 install（§8.9.4 同一套二次确认口径）。
 */
final class PluginCommands {

    private final AnsiRenderer renderer;
    private final ManagePluginsUseCase plugins;

    /** 待确认的卸载目标（null 表示没有）。 */
    private PluginId pendingUninstall;

    PluginCommands(AnsiRenderer renderer, ManagePluginsUseCase plugins) {
        this.renderer = renderer;
        this.plugins = plugins;
    }

    /** 是不是一条 `/plugins` 命令；不是就返回 false，调用方按原有分派继续。 */
    boolean handles(String[] parts) {
        return "/plugins".equals(parts[0]);
    }

    void run(String[] parts) {
        if (parts.length == 1) {
            list();
            return;
        }
        String action = parts[1];
        if ("install".equals(action)) {
            install(parts);
        } else if ("grant".equals(action)) {
            grant(parts);
        } else if ("revoke".equals(action)) {
            revoke(parts);
        } else if ("uninstall".equals(action)) {
            uninstall(parts);
        } else if ("help".equals(action)) {
            usage();
        } else {
            renderer.info("未知子命令：" + action);
            usage();
        }
    }

    /**
     * 有待确认的卸载时先处理它：同一目标再来一次才真卸，其余命令一律视为取消。
     * 返回 true 表示这一行已被消费。
     */
    boolean confirmIfPending(String[] parts) {
        if (pendingUninstall == null) {
            return false;
        }
        PluginId target = pendingUninstall;
        pendingUninstall = null;
        if ("/plugins".equals(parts[0]) && parts.length > 2 && "uninstall".equals(parts[1])
                && target.getValue().equals(parts[2])) {
            renderer.info("（已卸载：" + target.getValue() + "）");
            print(plugins.uninstall(target));
            return true;
        }
        renderer.info("（已取消卸载）");
        return false;
    }

    private void list() {
        for (io.github.zcd0831.jellyfish.domain.action.plugin.PluginManifest manifest : plugins.list()) {
            renderer.info("plugin " + manifest.id().getValue() + " v" + manifest.version()
                    + (manifest.isTrusted() ? " [trusted]" : " [untrusted]")
                    + " granted=" + manifest.grantedCapabilities()
                    + " tools=" + manifest.toolIds().size());
        }
    }

    private void install(String[] parts) {
        if (parts.length < 3) {
            usage();
            return;
        }
        PluginId pluginId = PluginId.of(parts[2]);
        boolean trusted = parts.length > 3 && "trusted".equals(parts[3]);
        print(plugins.install(pluginId, trusted));
        renderer.info("（已安装：" + pluginId.getValue() + (trusted ? "，已信任" : "，未信任")
                + "；要生效还需 /plugins grant " + pluginId.getValue() + " tools）");
    }

    private void grant(String[] parts) {
        if (parts.length < 4) {
            usage();
            return;
        }
        print(plugins.grant(PluginId.of(parts[2]), parts[3]));
        renderer.info("（已授权 " + parts[3] + "，立即生效）");
    }

    private void revoke(String[] parts) {
        if (parts.length < 4) {
            usage();
            return;
        }
        print(plugins.revoke(PluginId.of(parts[2]), parts[3]));
        renderer.info("（已收回 " + parts[3] + "）");
    }

    private void uninstall(String[] parts) {
        if (parts.length < 3) {
            usage();
            return;
        }
        PluginId target = PluginId.of(parts[2]);
        pendingUninstall = target;
        renderer.info("要卸载插件 " + target.getValue() + "（再输入一次 /plugins uninstall "
                + target.getValue() + " 确认；目录仍留在 plugins/ 下，由你自己删）");
    }

    private void usage() {
        renderer.info("/plugins  列出已安装的插件");
        renderer.info("/plugins install <id> [trusted]      只落安装意图，还没有效果");
        renderer.info("/plugins grant <id> tools            授权后插件才生效");
        renderer.info("/plugins revoke <id> tools           收回授权（停用）");
        renderer.info("/plugins uninstall <id>              摘掉运行期，目录自己删");
    }

    private void print(io.github.zcd0831.jellyfish.application.shared.Result<?> result) {
        if (!result.isOk()) {
            renderer.error(Messages.text(result.error().error().code()));
        }
    }
}
