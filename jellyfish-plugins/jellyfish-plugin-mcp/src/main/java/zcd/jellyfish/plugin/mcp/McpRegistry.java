package zcd.jellyfish.plugin.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP 运行期的共享状态：每个 server 的连接状态、已注册的工具、以及只读标记。
 * <p>
 * <b>为什么需要一个共享点</b>：这些事实有三个互不相识的读方——权限拦截要在「每次工具调用」的同步路径上
 * 回答「这个工具是不是只读」，{@code /mcp} 台账要回答「现在到底连上了没有」，
 * 而工具注册要在运行期不断改写它们。让连接对象自己持有，前两个读方就得去够一个随时可能被替换的引用。
 * <p>
 * <b>只读标记为什么要在这里留一份</b>：权限拦截处理器跑在同步路径上，它拿不到注册表
 * （插件看不到内核），因此只读这件事必须在注册那一刻就落在这里，而不是每次现算。
 * <p>
 * <b>状态是既成事实，读状态不推进状态</b>：连接成功、失败、收到通知分别由连接对象自己写，
 * 台账只读。这与熔断器「只有 {@code admit} / {@code recordSuccess} 能转移状态」是同一条口径。
 * <p>
 * 线程安全。
 *
 * @author zcd
 */
final class McpRegistry {

    /** 连接状态。 */
    enum State {

        /** 尚未开始连接（启动等待窗口内）。 */
        PENDING("等待连接"),

        /** 正在连接与握手。 */
        CONNECTING("连接中"),

        /** 已连接。 */
        CONNECTED("已连接"),

        /** 连接失败或进程已退出。 */
        FAILED("失败"),

        /** 插件已停止。 */
        STOPPED("已停止"),

        /** 配置里禁用。 */
        DISABLED("已禁用");

        /** 展示名。 */
        private final String displayName;

        /**
         * 构造状态。
         *
         * @param displayName 展示名
         */
        State(String displayName) {
            this.displayName = displayName;
        }

        /**
         * 获取展示名。
         *
         * @return 展示名
         */
        String displayName() {
            return displayName;
        }
    }

    /** 工具展开名 → 是否只读。 */
    private final Map<String, Boolean> readOnlyTools = new ConcurrentHashMap<String, Boolean>();

    /** server 标识 → 该 server 当前提供的工具展开名。 */
    private final Map<String, Set<String>> serverTools = new ConcurrentHashMap<String, Set<String>>();

    /** server 标识 → 运行状态；按标识排序以保证台账输出稳定。 */
    private final Map<String, ServerStatus> statuses = new ConcurrentSkipListMap<String, ServerStatus>();

    /**
     * 登记一个 server，初始状态为待连接。
     *
     * @param serverId 服务标识
     * @param state    初始状态
     * @param detail   初始说明
     */
    void register(String serverId, State state, String detail) {
        ServerStatus status = statuses.get(serverId);
        if (status == null) {
            ServerStatus created = new ServerStatus(serverId);
            ServerStatus previous = statuses.putIfAbsent(serverId, created);
            status = previous == null ? created : previous;
        }
        status.update(state, detail);
    }

    /**
     * 用一份新的工具清单替换某个 server 的工具集。
     * <p>
     * <b>整体替换而不是增量追加</b>：{@code tools/list} 返回的是「当前全部」，
     * 增量追加会让一个已被 server 删掉的工具永远留在只读标记里——而它的处理器已经不在了。
     *
     * @param serverId 服务标识
     * @param tools    新的工具清单
     */
    void replaceTools(String serverId, List<McpToolDefinition> tools) {
        Set<String> previous = serverTools.get(serverId);
        if (previous != null) {
            for (String name : previous) {
                readOnlyTools.remove(name);
            }
        }
        Set<String> current = new LinkedHashSet<String>();
        for (McpToolDefinition tool : tools) {
            readOnlyTools.put(tool.qualifiedName(), Boolean.valueOf(tool.readOnly()));
            current.add(tool.qualifiedName());
        }
        serverTools.put(serverId, Collections.unmodifiableSet(current));
        ServerStatus status = statuses.get(serverId);
        if (status != null) {
            status.toolCount = current.size();
        }
    }

    /**
     * 移除某个 server 的全部事实（连接断开时调用）。
     *
     * @param serverId 服务标识
     */
    void removeServer(String serverId) {
        Set<String> previous = serverTools.remove(serverId);
        if (previous != null) {
            for (String name : previous) {
                readOnlyTools.remove(name);
            }
        }
    }

    /**
     * 判断一个工具展开名是否由本插件提供。
     *
     * @param qualifiedName 工具展开名，可为 {@code null}
     * @return 由本插件提供返回 {@code true}
     */
    boolean isMcpTool(String qualifiedName) {
        return qualifiedName != null && readOnlyTools.containsKey(qualifiedName);
    }

    /**
     * 判断一个工具是否为只读。
     *
     * @param qualifiedName 工具展开名，可为 {@code null}
     * @return 只读返回 {@code true}；未知工具返回 {@code false}
     */
    boolean isReadOnly(String qualifiedName) {
        return Boolean.TRUE.equals(readOnlyTools.get(qualifiedName));
    }

    /**
     * 取某个 server 的工具展开名。
     *
     * @param serverId 服务标识
     * @return 不可变集合，保证非 {@code null}
     */
    Set<String> toolsOf(String serverId) {
        Set<String> tools = serverTools.get(serverId);
        return tools == null ? Collections.<String>emptySet() : tools;
    }

    /**
     * 更新某个 server 的状态。
     *
     * @param serverId 服务标识
     * @param state    新状态
     * @param detail   说明
     */
    void noteState(String serverId, State state, String detail) {
        ServerStatus status = statuses.get(serverId);
        if (status != null) {
            status.update(state, detail);
        }
    }

    /**
     * 记录一次失败。
     *
     * @param serverId 服务标识
     * @param message  失败原因
     */
    void noteFailure(String serverId, String message) {
        ServerStatus status = statuses.get(serverId);
        if (status != null) {
            status.lastError = message;
        }
        noteState(serverId, State.FAILED, message);
    }

    /**
     * 记一次「迟到响应被丢弃」。
     *
     * @param serverId 服务标识
     */
    void noteLateResponse(String serverId) {
        ServerStatus status = statuses.get(serverId);
        if (status != null) {
            status.lateResponses.incrementAndGet();
        }
    }

    /**
     * 记一条告警（例如清单被上限截断）。
     *
     * @param serverId 服务标识
     * @param message  告警文本
     */
    void noteWarning(String serverId, String message) {
        ServerStatus status = statuses.get(serverId);
        if (status != null) {
            status.lastWarning = message;
        }
    }

    /**
     * 取全部 server 的状态快照，按标识排序。
     *
     * @return 不可变列表，保证非 {@code null}
     */
    List<ServerStatus> snapshot() {
        return Collections.unmodifiableList(new ArrayList<ServerStatus>(statuses.values()));
    }

    /**
     * 取某个 server 的状态。
     *
     * @param serverId 服务标识
     * @return 状态；未登记时返回 {@code null}
     */
    ServerStatus statusOf(String serverId) {
        return statuses.get(serverId);
    }

    /**
     * 一个 server 的运行状态；字段用 {@code volatile}，因为写方是连接线程、读方是渲染线程。
     * <p>
     * 刻意做成可变对象而不是每次替换一个新快照：连接过程中的状态更新很频繁（每次通知、每次失败），
     * 而读方只想要「此刻大概是什么样」——为了这一点去引入一整套不可变快照的分配不划算。
     */
    static final class ServerStatus {

        /** 服务标识：台账需要它，而状态表本身只有值。 */
        private final String serverId;

        /** 当前状态。 */
        private volatile State state = State.PENDING;

        /** 状态说明。 */
        private volatile String detail = "";

        /** 已注册工具数。 */
        private volatile int toolCount;

        /** 最近一次失败原因。 */
        private volatile String lastError;

        /** 最近一条告警。 */
        private volatile String lastWarning;

        /** 迟到响应计数。 */
        private final AtomicLong lateResponses = new AtomicLong();

        /**
         * 构造状态。
         *
         * @param serverId 服务标识
         */
        private ServerStatus(String serverId) {
            this.serverId = serverId;
        }

        /**
         * 获取服务标识。
         *
         * @return 标识
         */
        String serverId() {
            return serverId;
        }

        /**
         * 更新状态与说明。
         *
         * @param state  新状态
         * @param detail 说明
         */
        private void update(State state, String detail) {
            this.state = state;
            this.detail = detail == null ? "" : detail;
        }

        /**
         * 获取当前状态。
         *
         * @return 状态
         */
        State state() {
            return state;
        }

        /**
         * 获取状态说明。
         *
         * @return 说明，保证非 {@code null}
         */
        String detail() {
            return detail;
        }

        /**
         * 获取已注册工具数。
         *
         * @return 工具数
         */
        int toolCount() {
            return toolCount;
        }

        /**
         * 获取最近一次失败原因。
         *
         * @return 失败原因，可能为 {@code null}
         */
        String lastError() {
            return lastError;
        }

        /**
         * 获取最近一条告警。
         *
         * @return 告警文本，可能为 {@code null}
         */
        String lastWarning() {
            return lastWarning;
        }

        /**
         * 获取迟到响应计数。
         *
         * @return 条数
         */
        long lateResponses() {
            return lateResponses.get();
        }
    }
}
