package zcd.jellyfish.infra.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code jellyfish.json} 的 {@code react.toolOutput} 段：工具结果的落盘与上下文治理参数。
 * <p>
 * <b>为什么单独成段而不是平铺进 {@code react}</b>：这几项只服务「一次工具结果太长时怎么办」
 * 这一件事，和轮次、预算、压缩策略不是同一类旋钮；平铺进去会让 {@code ReactSettings} 的构造器
 * 长到难以阅读，也难以看出它们互相牵制（保留文件越多、越占磁盘；保留最近消息越少、上下文越省）。
 * <p>
 * 四项的含义：
 * <ul>
 *     <li>{@code dir}：被截断工具结果的落盘根目录，缺省 {@code ~/.jellyfish/tool-outputs}。
 *     放在用户主目录而不是项目目录，是因为它是运行产物、不是项目内容，写进项目会污染工作区；</li>
 *     <li>{@code keepFiles}：每个会话在该目录下最多保留多少个结果文件，写 {@code 0} 关闭清理；</li>
 *     <li>{@code maxBytes}：每个会话在该目录下最多占用多少字节，写 {@code 0} 关闭清理；</li>
 *     <li>{@code spillMaxBytes}：单个结果最多落盘多少字节，写 {@code 0} 表示不限制。
 *     它面向的是「捕获期溢出」那条路径——无界输出（命令行的 {@code yes}）可以一直写，
 *     必须有一个人喊停；超出部分仍会继续被读取并丢弃，并如实标在回灌文本里。
 *     本类只如实携带用户写的值，与 {@code maxBytes} 的钳制关系在运行期处理（见 {@code SpillCapturingSink}）；</li>
 *     <li>{@code keepRecentMessages}：组装请求时，最近多少条消息里的工具结果保留完整内容，
 *     更早的、已落盘的旧结果替换成一行 stub，写 {@code 0} 关闭该项（全部保留）。</li>
 * </ul>
 * 非法值（空白目录、负数）回退到缺省值：配置问题不阻断启动是本仓库的既有口径，真正的限额在运行期兜底。
 * <p>
 * 不可变：所有字段在构造时确定，不存在 setter。
 *
 * @author zcd
 */
public class ToolOutputSettings {

    /** 落盘根目录缺省值。 */
    public static final String DEFAULT_DIR = "~/.jellyfish/tool-outputs";

    /** 每会话最多保留的结果文件数缺省值。 */
    public static final int DEFAULT_KEEP_FILES = 200;

    /** 每会话最多占用的字节数缺省值（50 MiB）。 */
    public static final long DEFAULT_MAX_BYTES = 50L * 1024L * 1024L;

    /**
     * 单个结果最多落盘的字节数缺省值（32 MiB）。
     * <p>
     * 刻意定得比 {@link #DEFAULT_MAX_BYTES} 小：一个结果文件不该把整个会话目录的预算吃干，
     * 否则「刚写的那个把自己之外的都挤掉」会让同一批结果里只剩下它一个。
     */
    public static final long DEFAULT_SPILL_MAX_BYTES = 32L * 1024L * 1024L;

    /** 上下文中保留完整工具结果的最近消息条数缺省值。 */
    public static final int DEFAULT_KEEP_RECENT_MESSAGES = 20;

    /** 落盘根目录。 */
    private final String dir;

    /** 每会话最多保留的文件数，{@code 0} 表示不清理。 */
    private final int keepFiles;

    /** 每会话最多占用的字节数，{@code 0} 表示不清理。 */
    private final long maxBytes;

    /** 单个结果最多落盘的字节数，{@code 0} 表示不限制。 */
    private final long spillMaxBytes;

    /** 上下文中保留完整工具结果的最近消息条数，{@code 0} 表示不裁剪。 */
    private final int keepRecentMessages;

    /** 配置里写错（并已按缺省值兜底）的地方，交给配置装载层报进事件面。 */
    private final List<String> warnings;

    /**
     * 构造缺省工具结果设置。
     */
    public ToolOutputSettings() {
        this(null, null, null, null, null);
    }

    /**
     * 反序列化与合并共用的构造器。
     *
     * @param dir                落盘根目录，空白或缺省按缺省值处理
     * @param keepFiles          每会话最多保留的文件数，负数或缺省按缺省值处理；
     *                           {@code 0} 合法（表示不清理）
     * @param maxBytes           每会话最多占用的字节数，负数或缺省按缺省值处理；
     *                           {@code 0} 合法（表示不清理）
     * @param spillMaxBytes      单个结果最多落盘的字节数，负数或缺省按缺省值处理；
     *                           {@code 0} 合法（表示不限制）
     * @param keepRecentMessages 保留完整工具结果的最近消息条数，负数或缺省按缺省值处理；
     *                           {@code 0} 合法（表示不裁剪）
     */
    @JsonCreator
    public ToolOutputSettings(@JsonProperty("dir") String dir,
                              @JsonProperty("keepFiles") Integer keepFiles,
                              @JsonProperty("maxBytes") Long maxBytes,
                              @JsonProperty("spillMaxBytes") Long spillMaxBytes,
                              @JsonProperty("keepRecentMessages") Integer keepRecentMessages) {
        List<String> collected = new ArrayList<String>();
        if (dir != null && dir.trim().isEmpty()) {
            collected.add("react.toolOutput.dir 为空白，已按缺省值 " + DEFAULT_DIR + " 处理");
        }
        this.dir = dir == null || dir.trim().isEmpty() ? DEFAULT_DIR : dir.trim();
        this.keepFiles = SettingsGuard.nonNegativeOrDefault(keepFiles, DEFAULT_KEEP_FILES,
                "react.toolOutput.keepFiles", collected);
        this.maxBytes = SettingsGuard.nonNegativeOrDefault(maxBytes, DEFAULT_MAX_BYTES,
                "react.toolOutput.maxBytes", collected);
        this.spillMaxBytes = SettingsGuard.nonNegativeOrDefault(spillMaxBytes, DEFAULT_SPILL_MAX_BYTES,
                "react.toolOutput.spillMaxBytes", collected);
        this.keepRecentMessages = SettingsGuard.nonNegativeOrDefault(keepRecentMessages,
                DEFAULT_KEEP_RECENT_MESSAGES, "react.toolOutput.keepRecentMessages", collected);
        this.warnings = Collections.unmodifiableList(collected);
    }

    /**
     * 获取「配置里写错的地方」（键名 + 原值 + 为什么非法 + 按什么处理）。
     * <p>
     * 与 {@code ReactSettings#warnings()} 同口径：本类不发事件，只把事实交给配置装载层，
     * 由它统一报进事件面。{@code dir} 为空白也在这里说一声——那多半是环境变量占位符没展开，
     * 而它的后果是「结果文件落进了别处」，只看最终目录是看不出来的。
     *
     * @return 不可修改列表，可能为空但不会为 {@code null}
     */
    public List<String> warnings() {
        return warnings;
    }

    /**
     * 获取落盘根目录。
     *
     * @return 落盘根目录，保证非空白
     */
    public String getDir() {
        return dir;
    }

    /**
     * 获取每会话最多保留的文件数。
     *
     * @return 文件数上限，保证非负；{@code 0} 表示不清理
     */
    public int getKeepFiles() {
        return keepFiles;
    }

    /**
     * 获取每会话最多占用的字节数。
     *
     * @return 字节上限，保证非负；{@code 0} 表示不清理
     */
    public long getMaxBytes() {
        return maxBytes;
    }

    /**
     * 获取单个结果最多落盘的字节数。
     *
     * @return 字节上限，保证非负；{@code 0} 表示不限制
     */
    public long getSpillMaxBytes() {
        return spillMaxBytes;
    }

    /**
     * 获取保留完整工具结果的最近消息条数。
     *
     * @return 消息条数，保证非负；{@code 0} 表示不裁剪
     */
    public int getKeepRecentMessages() {
        return keepRecentMessages;
    }

    /**
     * 判断是否与缺省值完全一致。
     * <p>
     * 供 {@link ReactSettings#isDefault()} 判断「整段是否什么都没配」，因此只比较是否等于缺省，
     * 不比较字段来源。
     *
     * @return 各项都等于缺省值返回 {@code true}
     */
    public boolean isDefault() {
        return DEFAULT_DIR.equals(dir)
                && keepFiles == DEFAULT_KEEP_FILES
                && maxBytes == DEFAULT_MAX_BYTES
                && spillMaxBytes == DEFAULT_SPILL_MAX_BYTES
                && keepRecentMessages == DEFAULT_KEEP_RECENT_MESSAGES;
    }
}
