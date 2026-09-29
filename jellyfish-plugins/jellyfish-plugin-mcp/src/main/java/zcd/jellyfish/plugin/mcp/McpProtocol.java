package zcd.jellyfish.plugin.mcp;

/**
 * MCP 协议的常量：方法名、错误码与协议版本。
 * <p>
 * <b>为什么收在一处</b>：方法名会同时出现在「发请求」与「识别来的是什么」两侧，
 * 各写一遍字面量的结果是一侧改了、另一侧静默不匹配，而现场表现是「某个功能就是不生效」。
 * <p>
 * <b>协议版本只用于握手声明，不用于版本判定</b>：规范要求 server 在不支持请求版本时
 * 回一个它支持的版本，因此这里声明一个较新的版本并接受对方的答复即可——
 * 拿它去比对、或在版本不符时拒绝连接，都会把「其实能通」变成「连不上」。
 * <p>
 * 不能实例化。
 *
 * @author zcd
 */
final class McpProtocol {

    /** JSON-RPC 版本。 */
    static final String JSONRPC_VERSION = "2.0";

    /** 握手时声明的协议版本。 */
    static final String PROTOCOL_VERSION = "2025-06-18";

    /** 初始化请求。 */
    static final String METHOD_INITIALIZE = "initialize";

    /** 初始化完成通知。 */
    static final String METHOD_INITIALIZED = "notifications/initialized";

    /** 列出工具。 */
    static final String METHOD_TOOLS_LIST = "tools/list";

    /** 调用工具。 */
    static final String METHOD_TOOLS_CALL = "tools/call";

    /** 工具清单变化通知。 */
    static final String METHOD_TOOLS_LIST_CHANGED = "notifications/tools/list_changed";

    /** 根目录询问（client 能力：roots）。 */
    static final String METHOD_ROOTS_LIST = "roots/list";

    /** 连通性探测。 */
    static final String METHOD_PING = "ping";

    /** 请求了本客户端未声明支持的方法。 */
    static final int ERROR_METHOD_NOT_FOUND = -32601;

    /** 本客户端内部错误。 */
    static final int ERROR_INTERNAL = -32603;

    /**
     * 工具类，禁止实例化。
     */
    private McpProtocol() {
    }
}
