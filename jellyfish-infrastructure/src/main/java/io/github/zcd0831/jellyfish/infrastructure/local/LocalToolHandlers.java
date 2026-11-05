package io.github.zcd0831.jellyfish.infrastructure.local;

import io.github.zcd0831.jellyfish.application.action.InternalToolHandler;
import io.github.zcd0831.jellyfish.application.action.InternalToolHandlers;
import io.github.zcd0831.jellyfish.application.action.SessionToolScope;
import io.github.zcd0831.jellyfish.application.action.ToolExecOutcome;
import io.github.zcd0831.jellyfish.application.shared.CancellationToken;
import io.github.zcd0831.jellyfish.domain.perception.session.Session;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 8 个本地能力的注册表：把名字与处理器对上，并把"越界/参数错/异常"统一收成**一种工具结果**。
 * 具体实现在 {@link FileToolHandlers} / {@link CommandToolHandler} / {@link UrlToolHandler}。
 * 规则出处：05-action §5.4.1 工具表、§5.8.1 规则 9（硬限制在同一处声明）。
 */
public final class LocalToolHandlers {

    static final String READ_FILE = "read_file";
    static final String READ_ARTIFACT = "read_artifact";
    static final String LIST_DIR = "list_dir";
    static final String GREP = "grep";
    static final String FETCH_URL = "fetch_url";
    static final String EDIT_FILE = "edit_file";
    static final String WRITE_FILE = "write_file";
    static final String DELETE_FILE = "delete_file";
    static final String RUN_COMMAND = "run_command";

    static final int MAX_READ_LINES = 2000;
    static final int MAX_ENTRIES = 500;
    static final int MAX_HITS = 200;
    static final int MAX_HIT_CHARS = 200;
    static final int DEFAULT_FETCH_CHARS = 8000;
    static final long DEFAULT_TIMEOUT_MS = 120_000;
    static final long MAX_TIMEOUT_MS = 600_000;
    static final int TAIL_LINES = 200;
    /** 等待子进程的分片：终止信号到达后最多这么久就杀进程树。 */
    static final long KILL_POLL_MILLIS = 100;

    /** TERM 之后给多少宽限再上 KILL：够 shell 跑完 trap，又不至于让用户等。 */
    static final long KILL_GRACE_MILLIS = 500;

    public static List<InternalToolHandlers.NamedHandler> all(Workspace workspace, UrlFetcher fetcher,
                                                             SessionToolScope scope) {
        return Arrays.asList(
                named(READ_FILE, FileToolHandlers.readFile(workspace)),
                readArtifact(workspace, scope),
                named(LIST_DIR, FileToolHandlers.listDir(workspace)),
                named(GREP, FileToolHandlers.grep(workspace)),
                named(FETCH_URL, UrlToolHandler.fetchUrl(fetcher)),
                named(EDIT_FILE, FileToolHandlers.editFile(workspace)),
                named(WRITE_FILE, FileToolHandlers.writeFile(workspace)),
                named(DELETE_FILE, FileToolHandlers.deleteFile(workspace)),
                named(RUN_COMMAND, CommandToolHandler.runCommand(workspace)));
    }

    private static InternalToolHandlers.NamedHandler named(String name, LocalTool tool) {
        return new InternalToolHandlers.NamedHandler(name, guarded(tool));
    }

    /** 越界与参数问题是"一种工具结果"，不是异常（§7.1 规则 9、规则 3）。 */
    private static InternalToolHandler guarded(final LocalTool tool) {
        return new InternalToolHandler() {
            @Override
            public ToolExecOutcome handle(Session session, Map<String, Object> args) {
                return handle(session, args, CancellationToken.never());
            }

            /** 终止信号要能到工具执行（08-application §8.9.4.1）。 */
            @Override
            public ToolExecOutcome handle(Session session, Map<String, Object> args, CancellationToken token) {
                try {
                    return ToolExecOutcome.plain(tool.run(args, token));
                } catch (java.util.concurrent.CancellationException terminated) {
                    // 终止是控制信号，不是「一种工具结果」：必须上抛，未闭合的调用由内循环补中止（§8.9.4.1）
                    throw terminated;
                } catch (Workspace.OutsideWorkspace | IllegalArgumentException e) {
                    return ToolExecOutcome.plain("工具错误：" + e.getMessage());
                } catch (Exception e) {
                    return ToolExecOutcome.plain("工具调用失败：" + e);
                }
            }
        };
    }

    /**
     * 验证裁判只能用这个工具读产物：路径必须在本次提交的 `submit_step.artifacts` 里。
     * 越界不是异常，是工具失败结果（06-planning §6.8.2、05-action §5.4.1）。
     */
    private static InternalToolHandlers.NamedHandler readArtifact(final Workspace workspace,
                                                                 final SessionToolScope scope) {
        return new InternalToolHandlers.NamedHandler(READ_ARTIFACT, new InternalToolHandler() {
            @Override
            public ToolExecOutcome handle(Session session, Map<String, Object> args) {
                Object raw = args.get("path");
                String path = raw == null ? "" : String.valueOf(raw);
                if (path.trim().isEmpty()) {
                    return ToolExecOutcome.plain("缺少 path");
                }
                if (!scope.allowsArtifact(session.id(), path)) {
                    return ToolExecOutcome.plain("该路径不在本次提交的产物清单里：" + path);
                }
                try {
                    return ToolExecOutcome.plain(FileToolHandlers.renderFile(workspace, args));
                } catch (Workspace.OutsideWorkspace | IllegalArgumentException bad) {
                    return ToolExecOutcome.plain("工具错误：" + bad.getMessage());
                } catch (IOException failed) {
                    return ToolExecOutcome.plain("工具调用失败：" + failed);
                }
            }
        });
    }

    /** 子进程环境白名单在 {@link CommandToolHandler}；这里留个入口给断言用。 */
    static Map<String, String> minimalEnvironment(Map<String, String> host) {
        return CommandToolHandler.minimalEnvironment(host);
    }
}
