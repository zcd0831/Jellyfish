package zcd.jellyfish.script;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.script.event.ScriptEventCatalog;
import zcd.jellyfish.api.extension.CommandDescriptor;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.api.plugin.PluginOwnerNamespace;
import zcd.jellyfish.script.codec.ExtensionCodecs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 脚本清单：一个脚本插件声明「我提供了什么」的那份静态文件（{@code manifest.json}）。
 * <p>
 * <b>它是注册的唯一来源</b>。脚本运行时不再上报注册，内核在插件 {@code start()} 期只读这份清单，
 * 因此换来三件事：Python 环境缺失不阻塞内核启动（清单在磁盘上，与解释器无关）、
 * 没有请求时脚本侧进程数为零（注册不需要进程）、worker 崩溃后直接重启即可（清单是权威，无需重注册）。
 * <p>
 * <b>代价是清单可能与实现漂移，因此本类的解析刻意「零容忍」</b>：
 * <ul>
 *     <li><b>未知键一律报错</b>，并把允许的键名列在错误里。这不是洁癖——
 *     {@code commands} 写成 {@code command} 的后果是「命令静默消失、没有任何报错」，
 *     而接口 {@code -parameters} 那类静默失效至少还有单测兜底，这类漂移没有；</li>
 *     <li>字段类型不对、必填缺失、同名重复、候选查询挂到不存在的命令上，全部报错；</li>
 *     <li>{@code contributions} 的取值必须在 {@link ExtensionCodecs} 里真实存在且是类型级扩展点——
 *     否则就是一份「声明了但内核不认识」的清单，同样属于静默失效。</li>
 * </ul>
 * 报错一律抛 {@link JellyfishException}，由扫描器收敛成「该脚本 ERROR 并跳过」，
 * <b>不影响同一语言下的其它脚本，也不让桥接插件 FAILED</b>。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ScriptManifest {

    /** 清单文件名。 */
    public static final String FILE_NAME = "manifest.json";

    /** 顶层允许的键。 */
    private static final Set<String> TOP_KEYS = keys("id", "entry", "tools", "commands", "commandOptions",
            "contributions", "events");

    /** 工具允许的键。 */
    private static final Set<String> TOOL_KEYS = keys("name", "description", "parameters", "required", "readOnly");

    /** 命令允许的键。 */
    private static final Set<String> COMMAND_KEYS = keys("name", "descriptor", "hasOptions");

    /** 命令名片允许的键。 */
    private static final Set<String> DESCRIPTOR_KEYS = keys("summary", "usage", "aliases");

    /** 候选查询条目允许的键。 */
    private static final Set<String> COMMAND_OPTION_KEYS = keys("name");

    /** 脚本标识。 */
    private final String id;

    /** 入口文件（相对脚本目录）。 */
    private final String entry;

    /** 工具声明。 */
    private final List<Tool> tools;

    /** 命令声明。 */
    private final List<Command> commands;

    /** 显式声明候选查询的命令名。 */
    private final Set<String> commandOptions;

    /** 类型级扩展点贡献声明（类型名）。 */
    private final Set<String> contributions;

    /** 想订阅的事件名。 */
    private final Set<String> events;

    /**
     * 构造清单。
     *
     * @param id             脚本标识
     * @param entry          入口文件
     * @param tools          工具声明
     * @param commands       命令声明
     * @param commandOptions 显式声明候选查询的命令名
     * @param contributions  类型级扩展点贡献声明
     * @param events         想订阅的事件名
     */
    private ScriptManifest(String id, String entry, List<Tool> tools, List<Command> commands,
                           Set<String> commandOptions, Set<String> contributions, Set<String> events) {
        this.id = id;
        this.entry = entry;
        this.tools = Collections.unmodifiableList(tools);
        this.commands = Collections.unmodifiableList(commands);
        this.commandOptions = Collections.unmodifiableSet(commandOptions);
        this.contributions = Collections.unmodifiableSet(contributions);
        this.events = Collections.unmodifiableSet(events);
    }

    /**
     * 解析并校验清单。
     *
     * @param json       清单正文，不可为 {@code null}
     * @param fallbackId {@code id} 缺失时使用的标识（通常取脚本目录名），不可为空白
     * @param codecs     扩展点编解码器注册表，用于校验 {@code contributions} 取值，不可为 {@code null}
     * @return 清单
     * @throws JellyfishException 存在语法、结构、类型或语义问题时抛出，消息里带具体位置
     */
    public static ScriptManifest parse(String json, String fallbackId, ExtensionCodecs codecs) {
        JsonNode root = ScriptJson.tree(json);
        if (root == null || !root.isObject()) {
            throw new JellyfishException("清单必须是 JSON 对象");
        }
        rejectUnknownKeys(root, "清单", TOP_KEYS);
        String id = resolveId(text(root, "id"), fallbackId);
        String entry = requireText(root, "entry");
        List<Tool> tools = parseTools(root.get("tools"));
        List<Command> commands = parseCommands(root.get("commands"));
        return new ScriptManifest(id, entry, tools, commands,
                parseCommandOptions(root.get("commandOptions"), commands), parseContributions(root, codecs),
                parseEvents(root.get("events")));
    }

    /**
     * 获取脚本标识。
     *
     * @return 脚本标识
     */
    public String id() {
        return id;
    }

    /**
     * 获取入口文件。
     *
     * @return 相对脚本目录的入口路径
     */
    public String entry() {
        return entry;
    }

    /**
     * 获取工具声明。
     *
     * @return 不可变列表
     */
    public List<Tool> tools() {
        return tools;
    }

    /**
     * 获取命令声明。
     *
     * @return 不可变列表
     */
    public List<Command> commands() {
        return commands;
    }

    /**
     * 获取显式声明候选查询的命令名。
     *
     * @return 不可变集合
     */
    public Set<String> commandOptions() {
        return commandOptions;
    }

    /**
     * 获取类型级扩展点贡献声明。
     *
     * @return 不可变集合
     */
    public Set<String> contributions() {
        return contributions;
    }

    /**
     * 获取想订阅的事件名。
     *
     * @return 不可变集合
     */
    public Set<String> events() {
        return events;
    }

    @Override
    public String toString() {
        return "ScriptManifest{id=" + id + ", tools=" + tools.size() + ", commands=" + commands.size() + '}';
    }

    /**
     * 解析脚本标识：缺省用目录名。
     *
     * @param declared   清单里声明的值，可为 {@code null}
     * @param fallbackId 目录名
     * @return 脚本标识
     * @throws JellyfishException 标识为空白、含空白字符、含路径分隔符或含 owner 命名空间分隔符时抛出
     */
    private static String resolveId(String declared, String fallbackId) {
        String value = declared == null ? fallbackId : declared;
        if (value == null || value.trim().isEmpty()) {
            throw new JellyfishException("id 缺失且无法从目录名推断");
        }
        // 复用 api 的子标识校验而不是在这里再写一份：脚本 id 会成为 owner 命名空间的子段，
        // 两处规则一旦漂移，就会出现「清单校验时通过、注册时却因为拼不出合法 owner 而失败」
        return PluginOwnerNamespace.requireChildId(value);
    }

    /**
     * 解析工具声明。
     *
     * @param node 工具数组节点，可为 {@code null}
     * @return 工具声明列表，保证非 {@code null}
     * @throws JellyfishException 结构或取值非法、工具名重复时抛出
     */
    private static List<Tool> parseTools(JsonNode node) {
        List<Tool> tools = new ArrayList<Tool>();
        if (node == null || node.isNull()) {
            return tools;
        }
        requireArray(node, "tools");
        Set<String> names = new LinkedHashSet<String>();
        for (int index = 0; index < node.size(); index++) {
            JsonNode element = node.get(index);
            String where = "tools[" + index + "]";
            requireObject(element, where);
            rejectUnknownKeys(element, where, TOOL_KEYS);
            String name = requireText(element, "name", where);
            if (!names.add(name)) {
                throw new JellyfishException(where + " 工具名重复: " + name);
            }
            tools.add(new Tool(name, text(element, "description"), parameters(element, where),
                    required(element, where), bool(element, "readOnly", false)));
        }
        return tools;
    }

    /**
     * 解析命令声明。
     *
     * @param node 命令数组节点，可为 {@code null}
     * @return 命令声明列表，保证非 {@code null}
     * @throws JellyfishException 结构或取值非法、命令名重复时抛出
     */
    private static List<Command> parseCommands(JsonNode node) {
        List<Command> commands = new ArrayList<Command>();
        if (node == null || node.isNull()) {
            return commands;
        }
        requireArray(node, "commands");
        Set<String> names = new LinkedHashSet<String>();
        for (int index = 0; index < node.size(); index++) {
            JsonNode element = node.get(index);
            String where = "commands[" + index + "]";
            requireObject(element, where);
            rejectUnknownKeys(element, where, COMMAND_KEYS);
            String name = requireText(element, "name", where);
            if (!names.add(name)) {
                throw new JellyfishException(where + " 命令名重复: " + name);
            }
            commands.add(new Command(name, descriptor(element.get("descriptor"), where),
                    bool(element, "hasOptions", false)));
        }
        return commands;
    }

    /**
     * 解析命令名片。
     *
     * @param node  名片节点，可为 {@code null}
     * @param where 报错位置前缀
     * @return 命令名片
     * @throws JellyfishException 结构或取值非法时抛出
     */
    private static CommandDescriptor descriptor(JsonNode node, String where) {
        String path = where + ".descriptor";
        if (node == null || node.isNull()) {
            return new CommandDescriptor(null, null, null);
        }
        requireObject(node, path);
        rejectUnknownKeys(node, path, DESCRIPTOR_KEYS);
        return new CommandDescriptor(text(node, "summary"), text(node, "usage"), strings(node.get("aliases"), path));
    }

    /**
     * 解析显式声明的候选查询。
     * <p>
     * <b>三条硬校验</b>（都属「早失败优于静默失效」）：
     * <ul>
     *     <li>候选查询必须挂在一条真实存在的命令上——内核按「已存在命令的规范名」查候选，
     *     挂在不存在的名字上永远不会被问到；</li>
     *     <li>不能同时用 {@code hasOptions} 与 {@code commandOptions} 声明同一条命令
     *     （必是作者写错，两种入口一个就够）；</li>
     *     <li>同名不得重复声明。</li>
     * </ul>
     *
     * @param node     候选查询数组节点，可为 {@code null}
     * @param commands 已解析的命令声明
     * @return 命令名集合，保证非 {@code null}
     * @throws JellyfishException 存在上述任一问题时抛出
     */
    private static Set<String> parseCommandOptions(JsonNode node, List<Command> commands) {
        Set<String> names = new LinkedHashSet<String>();
        if (node == null || node.isNull()) {
            return names;
        }
        requireArray(node, "commandOptions");
        Set<String> declared = new LinkedHashSet<String>();
        for (Command command : commands) {
            declared.add(command.name());
        }
        Set<String> implied = new LinkedHashSet<String>();
        for (Command command : commands) {
            if (command.hasOptions()) {
                implied.add(command.name());
            }
        }
        for (int index = 0; index < node.size(); index++) {
            JsonNode element = node.get(index);
            String where = "commandOptions[" + index + "]";
            requireObject(element, where);
            rejectUnknownKeys(element, where, COMMAND_OPTION_KEYS);
            String name = requireText(element, "name", where);
            if (!names.add(name)) {
                throw new JellyfishException(where + " 命令名重复: " + name);
            }
            if (!declared.contains(name)) {
                throw new JellyfishException(where + " 候选查询挂在未声明的命令上: " + name);
            }
            if (implied.contains(name)) {
                throw new JellyfishException(where + " 与 commands 的 hasOptions 重复声明同一命令: " + name);
            }
        }
        return names;
    }

    /**
     * 解析类型级扩展点贡献声明。
     *
     * @param root   清单根节点
     * @param codecs 扩展点编解码器注册表
     * @return 类型名集合，保证非 {@code null}
     * @throws JellyfishException 取值未知、非类型级或重复时抛出
     */
    private static Set<String> parseContributions(JsonNode root, ExtensionCodecs codecs) {
        Set<String> contributions = new LinkedHashSet<String>();
        for (String name : strings(root.get("contributions"), "contributions")) {
            if (!codecs.isKnown(name)) {
                throw new JellyfishException("contributions 含未知扩展点: " + name
                        + "（已知: " + codecs.names() + "）");
            }
            if (!codecs.isTypeLevel(name)) {
                throw new JellyfishException("contributions 只接受类型级扩展点，"
                        + name + " 带路由键，应在 tools / commands 里声明");
            }
            if (!contributions.add(name)) {
                throw new JellyfishException("contributions 重复声明: " + name);
            }
        }
        return contributions;
    }

    /**
     * 解析事件订阅声明。
     * <p>
     * 语法与白名单一起校验：**事件名必须是内核认识的**。
     * <p>
     * 在这里拒绝而不是等到运行期，是因为运行期那条路是静默的：订阅一个不存在的事件，
     * 表现是「处理器从来不执行」，而脚本作者会先怀疑自己的代码、再怀疑事件没触发，
     * 最后才怀疑名字写错了。清单是唯一一次「有人盯着看」的机会，因此放这里报，
     * 并且把可订阅的名字一起打出来——写错的人多半只是拼错或记错了全名。
     *
     * @param node 事件数组节点，可为 {@code null}
     * @return 事件名集合，保证非 {@code null}
     * @throws JellyfishException 存在空白项、重复项或不可订阅的事件名时抛出
     */
    private static Set<String> parseEvents(JsonNode node) {
        Set<String> events = new LinkedHashSet<String>();
        for (String name : strings(node, "events")) {
            if (!events.add(name)) {
                throw new JellyfishException("events 重复声明: " + name);
            }
            if (!ScriptEventCatalog.isObservable(name)) {
                throw new JellyfishException("events 声明了不可订阅的事件: " + name
                        + "；可订阅的有: " + ScriptEventCatalog.names());
            }
        }
        return events;
    }

    /**
     * 解析工具参数 Schema。
     *
     * @param node  工具节点
     * @param where 报错位置前缀
     * @return 参数 Schema 映射，保证非 {@code null}
     * @throws JellyfishException 值不是 JSON 对象时抛出
     */
    private static Map<String, Object> parameters(JsonNode node, String where) {
        JsonNode value = node.get("parameters");
        if (value == null || value.isNull()) {
            return Collections.emptyMap();
        }
        requireObject(value, where + ".parameters");
        return ScriptJson.treeToValue(value, new TypeReference<Map<String, Object>>() {
        });
    }

    /**
     * 解析必填参数名列表。
     *
     * @param node  工具节点
     * @param where 报错位置前缀
     * @return 必填参数名列表，保证非 {@code null}
     * @throws JellyfishException 值不是字符串数组时抛出
     */
    private static List<String> required(JsonNode node, String where) {
        return strings(node.get("required"), where + ".required");
    }

    /**
     * 解析字符串数组。
     *
     * @param node  数组节点，可为 {@code null}
     * @param where 报错位置前缀
     * @return 字符串列表，保证非 {@code null}
     * @throws JellyfishException 非数组、含非字符串元素或含空白元素时抛出
     */
    private static List<String> strings(JsonNode node, String where) {
        List<String> values = new ArrayList<String>();
        if (node == null || node.isNull()) {
            return values;
        }
        requireArray(node, where);
        for (int index = 0; index < node.size(); index++) {
            JsonNode element = node.get(index);
            if (!element.isTextual() || element.asText().trim().isEmpty()) {
                throw new JellyfishException(where + "[" + index + "] 必须是非空字符串");
            }
            values.add(element.asText().trim());
        }
        return values;
    }

    /**
     * 取可选文本字段。
     *
     * @param node  对象节点
     * @param field 字段名
     * @return 文本或 {@code null}
     * @throws JellyfishException 字段存在但不是字符串时抛出
     */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new JellyfishException(field + " 必须是字符串");
        }
        return value.asText();
    }

    /**
     * 取必填非空文本字段。
     *
     * @param node  对象节点
     * @param field 字段名
     * @return 文本
     * @throws JellyfishException 字段缺失或为空白时抛出
     */
    private static String requireText(JsonNode node, String field) {
        return requireText(node, field, null);
    }

    /**
     * 取必填非空文本字段，附带报错位置。
     *
     * @param node  对象节点
     * @param field 字段名
     * @param where 报错位置前缀，可为 {@code null}
     * @return 文本
     * @throws JellyfishException 字段缺失或为空白时抛出
     */
    private static String requireText(JsonNode node, String field, String where) {
        String value = text(node, field);
        if (value == null || value.trim().isEmpty()) {
            throw new JellyfishException((where == null ? "" : where + " ") + field + " 是非空字符串，必须提供");
        }
        return value.trim();
    }

    /**
     * 取可选布尔字段。
     *
     * @param node         对象节点
     * @param field        字段名
     * @param defaultValue 缺省值
     * @return 布尔值
     * @throws JellyfishException 字段存在但不是布尔值时抛出
     */
    private static boolean bool(JsonNode node, String field, boolean defaultValue) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isBoolean()) {
            throw new JellyfishException(field + " 必须是布尔值");
        }
        return value.asBoolean();
    }

    /**
     * 校验节点是对象。
     *
     * @param node  节点，可为 {@code null}
     * @param where 报错位置
     * @throws JellyfishException 不是对象时抛出
     */
    private static void requireObject(JsonNode node, String where) {
        if (node == null || !node.isObject()) {
            throw new JellyfishException(where + " 必须是 JSON 对象");
        }
    }

    /**
     * 校验节点是数组。
     *
     * @param node  节点
     * @param where 报错位置
     * @throws JellyfishException 不是数组时抛出
     */
    private static void requireArray(JsonNode node, String where) {
        if (!node.isArray()) {
            throw new JellyfishException(where + " 必须是数组");
        }
    }

    /**
     * 拒绝未知键，并把允许的键名写进错误消息。
     * <p>
     * 允许的键名必须出现在消息里：拼错一个字段名时，「含未知键 command」与
     * 「含未知键 command（允许: [id, entry, tools, commands, ...]）」对作者的帮助完全不同。
     *
     * @param node    对象节点
     * @param where   报错位置
     * @param allowed 允许的键名
     * @throws JellyfishException 存在未知键时抛出
     */
    private static void rejectUnknownKeys(JsonNode node, String where, Set<String> allowed) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new JellyfishException(where + " 含未知键 " + name + "（允许: " + allowed + "）");
            }
        }
    }

    /**
     * 构造键名集合。
     *
     * @param names 键名
     * @return 不可变集合，保持声明顺序
     */
    private static Set<String> keys(String... names) {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(names)));
    }

    /**
     * 工具声明：清单里的一个 {@code tools} 条目。
     * <p>
     * 它到内核侧的落点就是 {@link ToolDescriptor} 加一个转发处理器——两者的字段一一对应，
     * 因此这里不做任何「加工」，只做校验与承载。
     * <p>
     * 不可变，可安全跨线程传递。
     *
     * @author zcd
     */
    public static final class Tool {

        /** 工具名，同时是路由键。 */
        private final String name;

        /** 工具用途描述。 */
        private final String description;

        /** 参数的 JSON Schema properties。 */
        private final Map<String, Object> parameters;

        /** 必填参数名。 */
        private final List<String> required;

        /** 是否为只读工具。 */
        private final boolean readOnly;

        /**
         * 构造工具声明。
         *
         * @param name        工具名
         * @param description 用途描述
         * @param parameters  参数 Schema
         * @param required    必填参数名
         * @param readOnly    是否只读
         */
        private Tool(String name, String description, Map<String, Object> parameters, List<String> required,
                     boolean readOnly) {
            this.name = name;
            this.description = description;
            this.parameters = parameters;
            this.required = required;
            this.readOnly = readOnly;
        }

        /**
         * 获取工具名。
         *
         * @return 工具名
         */
        public String name() {
            return name;
        }

        /**
         * 获取用途描述。
         *
         * @return 用途描述，可为 {@code null}
         */
        public String description() {
            return description;
        }

        /**
         * 获取参数 Schema。
         *
         * @return 参数 Schema，保证非 {@code null}
         */
        public Map<String, Object> parameters() {
            return parameters;
        }

        /**
         * 获取必填参数名。
         *
         * @return 必填参数名，保证非 {@code null}
         */
        public List<String> required() {
            return required;
        }

        /**
         * 判断是否为只读工具。
         * <p>
         * 它参与内核 PLAN 模式的只读白名单，因此缺省为 {@code false}（可写）——
         * 与「只读是显式选择」同口径，误声明只读等于给了模型一个绕过 PLAN 的后门。
         *
         * @return 只读返回 {@code true}
         */
        public boolean readOnly() {
            return readOnly;
        }

        @Override
        public String toString() {
            return "Tool{name=" + name + ", readOnly=" + readOnly + '}';
        }
    }

    /**
     * 命令声明：清单里的一个 {@code commands} 条目。
     * <p>
     * {@code hasOptions} 表示「本命令也回答候选查询」，桥接层据此自动为同一命令名挂上
     * {@code CommandOptionRequest} 处理器，避免作者写两条必须保持同名的注册——那是
     * 「命令能执行、选择页永远空、且没有任何报错」这类问题的标准成因。
     * <p>
     * 不可变，可安全跨线程传递。
     *
     * @author zcd
     */
    public static final class Command {

        /** 命令名，同时是路由键。 */
        private final String name;

        /** 命令名片。 */
        private final CommandDescriptor descriptor;

        /** 是否同时回答候选查询。 */
        private final boolean hasOptions;

        /**
         * 构造命令声明。
         *
         * @param name       命令名
         * @param descriptor 命令名片
         * @param hasOptions 是否同时回答候选查询
         */
        private Command(String name, CommandDescriptor descriptor, boolean hasOptions) {
            this.name = name;
            this.descriptor = descriptor;
            this.hasOptions = hasOptions;
        }

        /**
         * 获取命令名。
         *
         * @return 命令名
         */
        public String name() {
            return name;
        }

        /**
         * 获取命令名片。
         *
         * @return 命令名片，保证非 {@code null}
         */
        public CommandDescriptor descriptor() {
            return descriptor;
        }

        /**
         * 判断是否同时回答候选查询。
         *
         * @return 是返回 {@code true}
         */
        public boolean hasOptions() {
            return hasOptions;
        }

        @Override
        public String toString() {
            return "Command{name=" + name + ", hasOptions=" + hasOptions + '}';
        }
    }
}
