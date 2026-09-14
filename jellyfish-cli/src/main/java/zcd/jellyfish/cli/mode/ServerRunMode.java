package zcd.jellyfish.cli.mode;

import zcd.jellyfish.cli.StartupOptions;
import zcd.jellyfish.cli.console.ConsoleIO;

/**
 * Server 模式：以 HTTP 服务运行，基于 Undertow，对外暴露能力接口给第三方 Web（尚未实现）。
 * <p>
 * <b>落地时的形态</b>：REST + SSE。{@code ReActListener} 本就是流式回调，SSE 天然映射；
 * 接口面为 {@code POST /sessions}（建会话）、{@code GET /sessions}（列表）、{@code GET /sessions/{id}}（概要）、
 * {@code POST /sessions/{id}/chat}（SSE 流式对话）、{@code POST /sessions/{id}/commands}（命令）、
 * {@code GET /commands}（结构化清单，供前端做菜单）、{@code DELETE /sessions/{id}}。
 * <p>
 * <b>安全口径</b>：默认只绑 {@code 127.0.0.1}，对外开放必须显式 {@code --host 0.0.0.0}；
 * 本轮口径为「无鉴权 + 只绑回环」，API key / token 另开一轮。鉴权落地前不要把这个端口暴露到公网。
 * <p>
 * <b>依赖坑（已查证，落地时必须处理）</b>：parent {@code pom.xml} 里声明的
 * {@code undertow.version = 2.4.3.Final} <b>不可用</b>——Undertow 自 2.3.0 起最低要求 Java 11，
 * 并已完成 {@code javax} → {@code jakarta} 迁移。本项目是 JDK 1.8 + {@code javax.*}，
 * 必须改用 2.2.x 线（最后一个版本 {@code 2.2.39.Final}）。
 * <p>
 * <b>模块归属</b>：开工时抽 {@code jellyfish-server} 模块，{@code jellyfish-cli} 只增加依赖，
 * {@code Launcher} 与参数解析不需要改动。
 *
 * @author zcd
 */
public final class ServerRunMode extends PlaceholderRunMode {

    /**
     * 构造 Server 占位模式。
     *
     * @param console 输出面板，不可为 {@code null}
     */
    public ServerRunMode(ConsoleIO console) {
        super(console);
    }

    @Override
    StartupOptions.Mode mode() {
        return StartupOptions.Mode.SERVER;
    }
}
