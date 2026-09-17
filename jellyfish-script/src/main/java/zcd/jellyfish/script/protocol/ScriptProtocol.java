package zcd.jellyfish.script.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.script.ScriptJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 跨语言运行时与脚本网关之间的线上协议：JSON-RPC 2.0 over stdio，每行一个 JSON 对象。
 * <p>
 * <b>为什么帧格式是「一行一个 JSON」而不是长度前缀</b>：脚本语言的调试手段几乎都是「打印一行看看」，
 * 而长度前缀会让任何一次误打印都把后续所有帧错位、恢复无从下手；行分隔则是「错一行、丢一行、继续」，
 * 与 {@link #parse(String)} 的宽容丢弃口径一致。代价是 JSON 里不能有裸换行——由 JSON 转义保证，
 * 协议层不存在多行帧。
 * <p>
 * <b>本类是纯函数</b>：只做「对象 ↔ 文本」和「文本 → 消息」的翻译，不持有连接、不发请求、不超时。
 * 有状态的部分（id 配对、等待与超时）在 {@link ScriptRpc}，两者的分界线是「是否有记忆」——
 * 这样协议形状可以完全离线单测，而并发问题只在一个类里面对。
 * <p>
 * <b>宽容度是刻意的</b>：解析失败的行返回 {@code null} 而不是抛异常。脚本与网关的 stdout 只有协议，
 * 但现实中总有误打印（用户脚本直接 {@code print}、解释器警告、第三方库的信息输出）。
 * 这些行不该让一次调用失败，也不该计入熔断——{@code null} 就是「丢掉这一行，装作没看见」。
 * <p>
 * 不可变、无状态，可安全跨线程使用。
 *
 * @author zcd
 */
public final class ScriptProtocol {

    /** 协议版本号。 */
    public static final String VERSION = "2.0";

    /** 下行方法：启动期下发脚本清单与设置。 */
    public static final String METHOD_INITIALIZE = "initialize";

    /** 下行方法：执行一次扩展点调用。 */
    public static final String METHOD_INVOKE = "invoke";

    /** 下行方法（通知）：推送内核事件。 */
    public static final String METHOD_EVENT = "event";

    /** 下行方法：杀某个脚本的 worker。 */
    public static final String METHOD_KILL_WORKER = "kill_worker";

    /** 下行方法：查询脚本状态。 */
    public static final String METHOD_STATUS = "status";

    /** 下行方法：优雅关闭。 */
    public static final String METHOD_SHUTDOWN = "shutdown";

    /** 上行方法：脚本发布通知。 */
    public static final String METHOD_EMIT_EVENT = "emit_event";

    /** 上行方法（通知）：网关上报 worker 状态变化。 */
    public static final String METHOD_WORKER_STATE = "worker_state";

    /** 错误码：脚本业务失败（脚本内异常）。 */
    public static final int CODE_SCRIPT_FAILURE = -32000;

    /** 错误码：脚本处于熔断中（Java 侧本地产生，不派发）。 */
    public static final int CODE_CIRCUIT_OPEN = -32001;

    /** 错误码：脚本不存在或未在清单中声明。 */
    public static final int CODE_UNKNOWN_SCRIPT = -32002;

    /** 错误码：结果超过传输上限。 */
    public static final int CODE_RESULT_TOO_LARGE = -32003;

    /** 错误码：清单与实现不一致（严格校验失败）。 */
    public static final int CODE_MANIFEST_MISMATCH = -32004;

    /** 错误码：方法不存在。 */
    public static final int CODE_METHOD_NOT_FOUND = -32601;

    /** 错误码：参数非法。 */
    public static final int CODE_INVALID_PARAMS = -32602;

    /** 错误码：网关内部错误。 */
    public static final int CODE_INTERNAL = -32603;

    /** 字段名：JSON-RPC 版本。 */
    private static final String FIELD_VERSION = "jsonrpc";

    /** 字段名：消息 id。 */
    private static final String FIELD_ID = "id";

    /** 字段名：方法名。 */
    private static final String FIELD_METHOD = "method";

    /** 字段名：参数。 */
    private static final String FIELD_PARAMS = "params";

    /** 字段名：成功结果。 */
    private static final String FIELD_RESULT = "result";

    /** 字段名：错误对象。 */
    private static final String FIELD_ERROR = "error";

    /** 字段名：错误码。 */
    private static final String FIELD_ERROR_CODE = "code";

    /** 字段名：错误描述。 */
    private static final String FIELD_ERROR_MESSAGE = "message";

    /** 参数键：脚本标识。 */
    public static final String PARAM_SCRIPT = "script";

    /** 参数键：扩展点类型名。 */
    public static final String PARAM_TYPE = "type";

    /** 参数键：扩展点请求载荷。 */
    public static final String PARAM_REQUEST = "request";

    /** 参数键：事件名。 */
    public static final String PARAM_EVENT = "event";

    /** 参数键：事件载荷。 */
    public static final String PARAM_PAYLOAD = "payload";

    /** 参数键：处置原因。 */
    public static final String PARAM_REASON = "reason";

    /** 参数键：脚本清单。 */
    public static final String PARAM_SCRIPTS = "scripts";

    /** 参数键：网关设置。 */
    public static final String PARAM_SETTINGS = "settings";

    /** 参数键：脚本目录绝对路径。 */
    public static final String PARAM_DIRECTORY = "directory";

    /** 参数键：入口文件绝对路径。 */
    public static final String PARAM_ENTRY = "entry";

    /** 参数键：清单摘要。 */
    public static final String PARAM_MANIFEST = "manifest";

    /** 参数键：初始化逐脚本结果。 */
    public static final String PARAM_OK = "ok";

    /** 参数键：脚本 id。 */
    public static final String PARAM_ID = "id";

    /** 参数键：初始化逐脚本失败原因。 */
    public static final String PARAM_ERROR = "error";

    /** 参数键：是否被受理。 */
    public static final String PARAM_ACCEPTED = "accepted";

    /** 已受理事件的标识（发布事件应答里的字段）。 */
    public static final String PARAM_EVENT_ID = "eventId";

    /** 参数键：worker 是否存活。 */
    public static final String PARAM_ALIVE = "alive";

    /** 参数键：是否已拉起 worker。 */
    public static final String PARAM_STARTED = "started";

    /** 参数键：worker 状态。 */
    public static final String PARAM_STATE = "state";

    /** 参数键：是否被杀掉。 */
    public static final String PARAM_KILLED = "killed";

    /** 参数键：PID 文件信息（{@code initialize} 应答里的字段）。 */
    public static final String PARAM_PID_FILE = "pidFile";

    /** 参数键：网关自己的 PID。 */
    public static final String PARAM_PID = "pid";

    /** 参数键：上一份 PID 文件里的内容描述（只报告不处置）。 */
    public static final String PARAM_STALE = "stale";

    /** 参数键：与 PID 文件相关的失败说明。 */
    public static final String PARAM_NOTICE = "notice";

    /**
     * 工具类，禁止实例化。
     */
    private ScriptProtocol() {
    }

    /**
     * 编码一个需要应答的请求。
     *
     * @param id     消息 id，必须唯一且大于零
     * @param method 方法名，不可为空白
     * @param params 参数，可为 {@code null}（编码为 JSON {@code null}）
     * @return 一帧文本，不含结尾换行
     */
    public static String request(long id, String method, JsonNode params) {
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put(FIELD_VERSION, VERSION);
        message.put(FIELD_ID, id);
        message.put(FIELD_METHOD, method);
        message.put(FIELD_PARAMS, params);
        return encode(message);
    }

    /**
     * 编码一个通知（不需要应答）。
     *
     * @param method 方法名，不可为空白
     * @param params 参数，可为 {@code null}
     * @return 一帧文本，不含结尾换行
     */
    public static String notification(String method, JsonNode params) {
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put(FIELD_VERSION, VERSION);
        message.put(FIELD_METHOD, method);
        message.put(FIELD_PARAMS, params);
        return encode(message);
    }

    /**
     * 编码一个成功应答，供网关回答上行请求（如 {@code emit_event}）。
     *
     * @param id     被应答的消息 id
     * @param result 结果载荷，可为 {@code null}
     * @return 一帧文本，不含结尾换行
     */
    public static String response(long id, JsonNode result) {
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put(FIELD_VERSION, VERSION);
        message.put(FIELD_ID, id);
        message.put(FIELD_RESULT, result);
        return encode(message);
    }

    /**
     * 编码一个失败应答。
     * <p>
     * 错误走协议的错误对象而不是「结果里塞一个 error 字段」，是因为后者会被调用方当成正常结果：
     * 工具的错误结果会被回灌给模型当输出，语义就此静默错位。
     *
     * @param id      被应答的消息 id
     * @param code    错误码
     * @param message 错误描述
     * @return 一帧文本，不含结尾换行
     */
    public static String errorResponse(long id, int code, String message) {
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put(FIELD_ERROR_CODE, code);
        error.put(FIELD_ERROR_MESSAGE, message);
        Map<String, Object> envelope = new LinkedHashMap<String, Object>();
        envelope.put(FIELD_VERSION, VERSION);
        envelope.put(FIELD_ID, id);
        envelope.put(FIELD_ERROR, error);
        return encode(envelope);
    }

    /**
     * 解析一帧文本。
     * <p>
     * 空白行、非 JSON 文本、JSON 但不是对象、既没有 id 也没有方法名的对象，全部返回 {@code null}
     * 由调用方丢弃并告警。这里不抛异常：协议层的「脏行」是可预期的现实（用户脚本误打印、
     * 解释器警告），把它升级成失败会让「脚本打了一行日志」变成「工具调用失败」。
     * <p>
     * <b>不校验 {@code jsonrpc} 字段</b>：它是常量，校验它只能拒绝掉手写的测试桩与将来的其他语言实现，
     * 而换不来任何真实保障——协议版本不一致时真正会暴露问题的是方法名与字段形状。
     *
     * @param line 帧文本，可为 {@code null}
     * @return 消息；无法识别时返回 {@code null}
     */
    public static Message parse(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        JsonNode root;
        try {
            root = ScriptJson.tree(line);
        } catch (JellyfishException e) {
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        Long id = idOf(root.get(FIELD_ID));
        String method = textOf(root.get(FIELD_METHOD));
        JsonNode params = root.get(FIELD_PARAMS);
        if (method != null) {
            return Message.incoming(id, method, params);
        }
        if (id == null) {
            return null;
        }
        JsonNode error = root.get(FIELD_ERROR);
        if (error != null && error.isObject()) {
            return Message.failure(id, codeOf(error.get(FIELD_ERROR_CODE)), textOf(error.get(FIELD_ERROR_MESSAGE)));
        }
        return Message.success(id, root.get(FIELD_RESULT));
    }

    /**
     * 把消息映射序列化成 JSON 文本。
     *
     * @param message 消息映射
     * @return JSON 文本
     * @throws JellyfishException 序列化失败时抛出
     */
    private static String encode(Map<String, Object> message) {
        return ScriptJson.write(message);
    }

    /**
     * 读取消息 id。
     * <p>
     * 只接受整数：id 由发送方自增生成，接受浮点或字符串只会让配对表里出现两种键，
     * 而「响应找不到等待者」的表现是「调用卡到超时」，现场离原因很远。
     *
     * @param node id 节点，可为 {@code null}
     * @return id；不是整数时返回 {@code null}
     */
    private static Long idOf(JsonNode node) {
        return node != null && node.isIntegralNumber() ? Long.valueOf(node.asLong()) : null;
    }

    /**
     * 读取字符串字段。
     *
     * @param node 字段节点，可为 {@code null}
     * @return 文本；不是非空字符串时返回 {@code null}
     */
    private static String textOf(JsonNode node) {
        if (node == null || !node.isTextual() || node.asText().trim().isEmpty()) {
            return null;
        }
        return node.asText();
    }

    /**
     * 读取错误码。
     *
     * @param node 错误码节点，可为 {@code null}
     * @return 错误码；缺失或非数字时退化为 {@link #CODE_INTERNAL}
     */
    private static int codeOf(JsonNode node) {
        return node != null && node.isNumber() ? node.asInt() : CODE_INTERNAL;
    }

    /**
     * 一帧协议消息。
     * <p>
     * 三种形状由「有无方法名 / 有无 id」区分，而不是靠三个子类型：
     * <ul>
     *     <li><b>应答</b>：有 id、无方法名；有 {@code error} 则为失败应答；</li>
     *     <li><b>通知</b>：有方法名、无 id，不需要应答；</li>
     *     <li><b>上行请求</b>：有方法名、有 id，需要应答。</li>
     * </ul>
     * 合成一个类是因为接收方处理它们时用的是同一段代码（{@link ScriptRpc#accept(String)}），
     * 拆成三个类型只会让那段代码退化成 {@code instanceof} 链。
     * <p>
     * 不可变，可安全跨线程传递。
     *
     * @author zcd
     */
    public static final class Message {

        /** 消息 id；通知为 {@code null}。 */
        private final Long id;

        /** 方法名；应答为 {@code null}。 */
        private final String method;

        /** 参数；应答为 {@code null}。 */
        private final JsonNode params;

        /** 成功结果。 */
        private final JsonNode result;

        /** 错误码；无错误时为 {@code 0}。 */
        private final int errorCode;

        /** 错误描述；无错误时为 {@code null}。 */
        private final String errorMessage;

        /**
         * 构造消息。
         *
         * @param id           消息 id
         * @param method       方法名
         * @param params       参数
         * @param result       成功结果
         * @param errorCode    错误码
         * @param errorMessage 错误描述
         */
        private Message(Long id, String method, JsonNode params, JsonNode result, int errorCode,
                        String errorMessage) {
            this.id = id;
            this.method = method;
            this.params = params;
            this.result = result;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        /**
         * 构造收到的请求或通知。
         *
         * @param id     消息 id，通知为 {@code null}
         * @param method 方法名
         * @param params 参数
         * @return 消息
         */
        static Message incoming(Long id, String method, JsonNode params) {
            return new Message(id, method, params, null, 0, null);
        }

        /**
         * 构造成功应答。
         *
         * @param id     消息 id
         * @param result 结果载荷
         * @return 消息
         */
        static Message success(Long id, JsonNode result) {
            return new Message(id, null, null, result, 0, null);
        }

        /**
         * 构造失败应答。
         *
         * @param id      消息 id
         * @param code    错误码
         * @param message 错误描述
         * @return 消息
         */
        static Message failure(Long id, int code, String message) {
            return new Message(id, null, null, null, code, message);
        }

        /**
         * 获取消息 id。
         *
         * @return 消息 id；通知为 {@code null}
         */
        public Long id() {
            return id;
        }

        /**
         * 获取方法名。
         *
         * @return 方法名；应答为 {@code null}
         */
        public String method() {
            return method;
        }

        /**
         * 获取参数。
         *
         * @return 参数节点；无参数时为 {@code null}
         */
        public JsonNode params() {
            return params;
        }

        /**
         * 获取成功结果。
         *
         * @return 结果节点；失败应答或请求/通知为 {@code null}
         */
        public JsonNode result() {
            return result;
        }

        /**
         * 获取错误码。
         *
         * @return 错误码；无错误时为 {@code 0}
         */
        public int errorCode() {
            return errorCode;
        }

        /**
         * 获取错误描述。
         *
         * @return 错误描述；无错误时为 {@code null}
         */
        public String errorMessage() {
            return errorMessage;
        }

        /**
         * 判断是否为应答。
         *
         * @return 应答返回 {@code true}
         */
        public boolean isResponse() {
            return method == null;
        }

        /**
         * 判断是否为失败应答。
         *
         * @return 失败返回 {@code true}
         */
        public boolean isFailure() {
            return isResponse() && errorCode != 0;
        }

        /**
         * 判断是否需要应答。
         *
         * @return 带 id 的请求返回 {@code true}（通知返回 {@code false}）
         */
        public boolean needsResponse() {
            return method != null && id != null;
        }

        /**
         * 读取参数里的字符串字段。
         *
         * @param name 字段名
         * @return 文本；字段缺失或不是非空字符串时返回 {@code null}
         */
        public String paramText(String name) {
            return params == null ? null : textOf(params.get(name));
        }

        /**
         * 读取参数里的节点字段。
         *
         * @param name 字段名
         * @return 节点；字段缺失时返回 {@code null}
         */
        public JsonNode paramNode(String name) {
            return params == null ? null : params.get(name);
        }

        @Override
        public String toString() {
            if (isResponse()) {
                return "Message{id=" + id + ", " + (isFailure() ? "error=" + errorCode + " " + errorMessage
                        : "result=" + result) + '}';
            }
            return "Message{id=" + id + ", method=" + method + '}';
        }
    }
}
