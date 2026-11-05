package io.github.zcd0831.jellyfish.shell.tui;

import io.github.zcd0831.jellyfish.application.action.ManagePluginsUseCase;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.perception.AdjustPolicyUseCase;
import io.github.zcd0831.jellyfish.application.perception.ManageSessionUseCase;
import io.github.zcd0831.jellyfish.application.perception.SwitchModelUseCase;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.application.shared.UseCaseError;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * `/` 命令的分派与呈现：把一行输入变成用例调用，再把结果画回屏幕。
 * <p>
 * 从 {@link TuiApp} 摘出来是为了让输入循环只关心"读行、提问、跑一轮"；
 * 当前会话通过 {@link SessionCursor} 共享，不反向依赖外壳。
 * 规则出处：08-application §8.9.4.2（外壳只调用例）、§8.9.4（终止/归档/删除）。
 */
final class CommandDispatcher {

    private static final int REPLAY_RECORDS = 100;

    private final AnsiRenderer renderer;
    private final TurnStats stats;
    private final ManageSessionUseCase manageSession;
    private final AdjustPolicyUseCase adjustPolicy;
    private final SwitchModelUseCase switchModel;
    private final ManagePluginsUseCase plugins;
    private final CatalogRepository catalog;
    private final SessionCursor cursor;

    CommandDispatcher(AnsiRenderer renderer, TurnStats stats, ManageSessionUseCase manageSession,
                      AdjustPolicyUseCase adjustPolicy, SwitchModelUseCase switchModel,
                      ManagePluginsUseCase plugins, CatalogRepository catalog, SessionCursor cursor) {
        this.renderer = renderer;
        this.stats = stats;
        this.manageSession = manageSession;
        this.adjustPolicy = adjustPolicy;
        this.switchModel = switchModel;
        this.plugins = plugins;
        this.catalog = catalog;
        this.cursor = cursor;
        register();
    }

    /** 处理一行 `/` 命令；返回后调用方继续读下一行。 */
    /** 一条命令的处理器：参数已按空白切好。 */
    private interface Handler {
        void run(String[] parts);
    }

    /**
     * 待确认的删除目标：`/delete` 是递归删掉整个会话族、无法撤销，
     * 所以外壳要先二次确认（08-application §8.9.4「需外壳先做二次确认」）。
     */
    private SessionId pendingDelete;

    /** 命令 → 处理器；缺参数与未知命令都走 {@link #list(String)}（保持原有口径）。 */
    private final Map<String, Handler> handlers = new LinkedHashMap<String, Handler>();

    private void register() {
        registerSessionCommands();
        registerModelCommands();
        registerCatalogCommands();
    }

    /** 会话类：建/切/归档/删除/终止。 */
    private void registerSessionCommands() {
        handlers.put("/new", new Handler() {
            @Override
            public void run(String[] parts) {
                // 当前会话留在 sessions/ 里，下一条输入开新会话：不必退出重进
                cursor.clear();
                stats.reset();
                renderer.info("（新会话：下一条输入将开启）");
            }
        });
        handlers.put("/sessions", new Handler() {
            @Override
            public void run(String[] parts) {
                listSessions();
            }
        });
        handlers.put("/open", new Handler() {
            @Override
            public void run(String[] parts) {
                if (parts.length > 1) {
                    openSession(SessionId.of(parts[1]));
                } else {
                    list(parts[0]);
                }
            }
        });
        handlers.put("/archive", new Handler() {
            @Override
            public void run(String[] parts) {
                print(manageSession.archive(cursor.current()));
                cursor.clear();
                renderer.info("（已归档：应用不再列出该会话）");
            }
        });
        handlers.put("/delete", new Handler() {
            @Override
            public void run(String[] parts) {
                SessionId target = cursor.current();
                if (target == null) {
                    return;
                }
                renderer.info("要删除会话 " + target.getValue() + "（含它的全部子会话），此操作不可撤销。"
                        + "\n再输入一次 /delete 确认，输入其它内容即取消。");
                pendingDelete = target;
            }
        });
        handlers.put("/cancel", new Handler() {
            @Override
            public void run(String[] parts) {
                cancelSession(parts.length > 1 ? SessionId.of(parts[1]) : cursor.current());
            }
        });
    }

    /** 模型与策略：改了就立刻生效。 */
    private void registerModelCommands() {
        handlers.put("/model", new Handler() {
            @Override
            public void run(String[] parts) {
                if (parts.length > 1) {
                    print(switchModel.switchTo(cursor.current(),
                            io.github.zcd0831.jellyfish.domain.shared.ModelId.of(parts[1])));
                    renderer.info("（当前模型：" + parts[1] + "）");
                } else {
                    list(parts[0]);
                }
            }
        });
        handlers.put("/policy", new Handler() {
            @Override
            public void run(String[] parts) {
                Map<String, Object> changes = new LinkedHashMap<String, Object>();
                for (int i = 1; i < parts.length; i++) {
                    int eq = parts[i].indexOf('=');
                    if (eq > 0) {
                        changes.put(parts[i].substring(0, eq), parts[i].substring(eq + 1));
                    }
                }
                print(adjustPolicy.adjust(changes));
                renderer.info("策略已更新（立即生效）");
            }
        });
    }

    /** 目录类：只读列出。 */
    private void registerCatalogCommands() {
        handlers.put("/tools", listOnly());
        handlers.put("/agents", listOnly());
        handlers.put("/models", listOnly());
        handlers.put("/providers", listOnly());
        handlers.put("/skills", listOnly());
        handlers.put("/plugins", listOnly());
    }

    /** 二次确认后的真正删除：只有走到这里才会递归删掉整个会话族。 */
    private void confirmDelete(SessionId target) {
        print(manageSession.delete(target));
        if (target.equals(cursor.current())) {
            cursor.clear();
        }
        renderer.info("（已删除：" + target.getValue() + " 及其子会话）");
    }

    /** 目录类命令：只读地列出当前目录内容。 */
    private Handler listOnly() {
        return new Handler() {
            @Override
            public void run(String[] parts) {
                list(parts[0]);
            }
        };
    }

    /** 处理一行 `/` 命令；返回后调用方继续读下一行。 */
    void command(String line) {
        String[] parts = line.split("\\s+");
        if (pendingDelete != null) {
            SessionId target = pendingDelete;
            pendingDelete = null;
            if ("/delete".equals(parts[0])) {
                confirmDelete(target);
                return;
            }
            renderer.info("（已取消删除）");
        }
        String name = parts[0];
        if ("/help".equals(name)) {
            renderer.info("/sessions /open <id> /new /archive /delete /cancel [id] /policy k=v "
                    + "/model <id> /tools /agents /models /providers /skills /plugins /exit");
            return;
        }
        if (cursor.current() == null && Commands.needsSession(name)) {
            renderer.info("（还没有会话）");
            return;
        }
        Handler handler = handlers.get(name);
        if (handler == null) {
            list(name);
            return;
        }
        handler.run(parts);
    }

    private void cancelSession(SessionId sessionId) {
        if (sessionId == null) {
            renderer.info("（还没有会话）");
            return;
        }
        Result<Void> cancelled = manageSession.cancel(sessionId);
        if (cancelled.isOk()) {
            renderer.info("（已终止：" + sessionId.getValue() + "；本轮不写进历史，可继续输入）");
        } else {
            printError(cancelled.error());
        }
    }

    private void openSession(SessionId sessionId) {
        Result<io.github.zcd0831.jellyfish.application.perception.SessionTranscript> loaded =
                manageSession.transcript(sessionId, REPLAY_RECORDS);
        if (!loaded.isOk()) {
            printError(loaded.error());
            return;
        }
        cursor.moveTo(sessionId);
        stats.reset();
        replay(loaded.value());
        stats.restoreContextLabel(loaded.value().contextLabel());
        stats.restoreUsage(loaded.value().consumedTokens(), loaded.value().budgetLimitTokens());
    }

    private void replay(io.github.zcd0831.jellyfish.application.perception.SessionTranscript transcript) {
        renderer.divider("—— 会话：" + transcript.title() + "（" + transcript.modelId().getValue()
                + "，" + dateFormat().format(new java.util.Date(transcript.createdDate())) + "） ——");
        for (io.github.zcd0831.jellyfish.domain.perception.session.Record record : transcript.records()) {
            switch (record.role()) {
                case USER:
                    renderer.userPrefix();
                    renderer.append(record.content());
                    renderer.endTurn();
                    break;
                case ASSISTANT:
                    renderer.assistantPrefix();
                    renderer.append(record.content());
                    renderer.endTurn();
                    break;
                case TOOL_REQUEST:
                    renderer.info("↳ " + record.content());
                    break;
                case TOOL_RESULT:
                    renderer.info("✓ " + firstLine(record.content(), 80));
                    break;
                case SUMMARY:
                    renderer.info("〔更早对话已压缩为摘要〕");
                    break;
                default:
                    // SYSTEM（经验/SOP 注入）不是对话内容，回放时不显示
            }
        }
        if (transcript.hasEarlier()) {
            renderer.info("（上面是最近 " + REPLAY_RECORDS + " 条，更早的没有显示）");
        }
        StringBuilder line = new StringBuilder("—— 上下文 "
                + transcript.contextLabel().replaceAll("\\s*/\\s*", "/"));
        if (transcript.consumedTokens() > 0) {
            line.append(" / 累计 ").append(transcript.consumedTokens());
            if (transcript.budgetLimitTokens() > 0) {
                line.append(" / 上限 ").append(transcript.budgetLimitTokens());
            }
        }
        renderer.divider(line.append(" ——").toString());
        renderer.info("（已切到该会话，继续输入即可）");
    }

    private void listSessions() {
        Result<java.util.List<io.github.zcd0831.jellyfish.application.perception.port.SessionSummary>> listed =
                manageSession.list();
        if (!listed.isOk()) {
            printError(listed.error());
            return;
        }
        for (io.github.zcd0831.jellyfish.application.perception.port.SessionSummary summary : listed.value()) {
            String marker = summary.isSubSession() ? "  └ " : "";
            renderer.info(marker + summary.id().getValue() + "  " + summary.title()
                    + "  " + summary.modelId().getValue());
        }
    }

    private void list(String what) {
        if ("/tools".equals(what)) {
            for (io.github.zcd0831.jellyfish.domain.action.tool.ToolDefinition tool : catalog.tools()) {
                renderer.info("tool " + tool.id().getValue() + " [" + tool.status() + "] "
                        + tool.source().getType());
            }
        } else if ("/agents".equals(what)) {
            for (io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition agent : catalog.agents()) {
                renderer.info("agent " + agent.id().getValue() + " [" + agent.status() + "] " + agent.source());
            }
        } else if ("/models".equals(what)) {
            for (io.github.zcd0831.jellyfish.domain.action.model.Model model : catalog.models()) {
                renderer.info("model " + model.id().getValue() + " [" + model.status() + "] " + model.modelName());
            }
        } else if ("/providers".equals(what)) {
            for (io.github.zcd0831.jellyfish.domain.action.model.Provider provider : catalog.providers()) {
                renderer.info("provider " + provider.id().getValue() + " [" + provider.status() + "] "
                        + provider.protocol());
            }
        } else if ("/skills".equals(what)) {
            for (io.github.zcd0831.jellyfish.domain.memory.skill.SkillDefinition skill : catalog.skills()) {
                renderer.info("skill " + skill.id().getValue() + " [" + skill.status() + "] " + skill.name());
            }
        } else if ("/plugins".equals(what)) {
            for (io.github.zcd0831.jellyfish.domain.action.plugin.PluginManifest manifest
                    : plugins.list()) {
                renderer.info("plugin " + manifest.id().getValue() + " v" + manifest.version()
                        + (manifest.isTrusted() ? " [trusted]" : " [untrusted]")
                        + " granted=" + manifest.grantedCapabilities()
                        + " tools=" + manifest.toolIds().size());
            }
        } else {
            renderer.info("未知命令：" + what + "（/help 查看）");
        }
    }

    void print(Result<?> result) {
        if (!result.isOk()) {
            printError(result.error());
        }
    }

    void printError(UseCaseError error) {
        renderer.error(Messages.text(error.error().code()));
    }

    private static java.text.SimpleDateFormat dateFormat() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT);
    }

    private static String firstLine(String text, int limit) {
        if (text == null) {
            return "";
        }
        int newline = text.indexOf('\n');
        String line = newline < 0 ? text : text.substring(0, newline);
        return line.length() > limit ? line.substring(0, limit) + "…" : line;
    }
}
