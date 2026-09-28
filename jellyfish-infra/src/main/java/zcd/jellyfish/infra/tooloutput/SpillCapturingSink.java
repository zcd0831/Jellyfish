package zcd.jellyfish.infra.tooloutput;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zcd.jellyfish.api.extension.ToolOutputSink;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * 捕获期输出 sink：把无界输出的捕获过程本身变成「内存有界 + 内容不丢」。
 * <p>
 * <b>它解决什么问题</b>：命令行输出是<b>无界且不可再取</b>的——整份物化进内存会撑爆 JVM
 * （{@code yes} 一分钟能产出几个 GB），而工具层自己截断又会让被丢掉的部分永久消失
 * （不像文件还能按 {@code offset} 重读）。本类让内核在输出产生的过程中就接管：
 * 攒到阈值之前留在内存、超过阈值转写磁盘、回灌时只给头尾预览加落盘路径。
 * <p>
 * <b>内存占用与输出体积无关</b>：缓冲结束之后，内存里只剩「头一段 + 尾一个窗口」，两者都由
 * 回灌预算封顶，因此无论命令产出多少字节，JVM 的占用都在同一个量级。这是本方案相对于
 * 「先物化再截断」的关键区别，也是它不需要一个「内存天花板」去赌的原因。
 * <p>
 * <b>落盘只在溢出时发生</b>：短输出（{@code ls}、{@code git status}）全程在内存里结束，
 * 不产生任何文件。这一点与「总是写工作文件」的实现不同，代价是不能在命令运行中回读已完成的部分——
 * 而内核并不需要那个能力。
 * <p>
 * <b>线程安全</b>：{@link #write(String)} 会被 stdout 与 stderr 两条泵线程并发调用。
 *
 * @author zcd
 */
final class SpillCapturingSink implements ToolOutputSink {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(SpillCapturingSink.class);

    /** 渲染后仍超预算时最多校正多少轮。 */
    private static final int MAX_FIT_ROUNDS = ToolOutputEnvelope.MAX_FIT_ROUNDS;

    /** 落盘存储。 */
    private final ToolOutputStore store;

    /** 会话标识，可为 {@code null}。 */
    private final String sessionId;

    /** 工具调用标识，可为 {@code null}。 */
    private final String toolCallId;

    /** 工具名，可为 {@code null}。 */
    private final String toolName;

    /** 回灌给模型的字符预算。 */
    private final int maxChars;

    /** 单个结果的落盘字节上限，{@code 0} 表示不限制。 */
    private final long spillMaxBytes;

    /** 实时输出的旁路接收者，可为 {@code null}。 */
    private final Consumer<String> onChunk;

    /** 元数据行（正文首行），始终可见。 */
    private final StringBuilder summary = new StringBuilder();

    /** 状态锁：写入、压缩、收尾共用。 */
    private final Object lock = new Object();

    /** 溢出前的内存缓冲；转入溢写后置为 {@code null}。 */
    private StringBuilder buffered = new StringBuilder();

    /** 头窗口：正文开头的一段，转入溢写后开始保留。 */
    private final StringBuilder head = new StringBuilder();

    /** 尾窗口：正文结尾的一段（摊大后裁前）。 */
    private final StringBuilder tail = new StringBuilder();

    /** 正文累计字符数（不含元数据行）。 */
    private long bodyChars;

    /** 正文累计换行数，用于统计省略了多少行。 */
    private long bodyNewlines;

    /** 是否已转入溢写期。 */
    private boolean spilled;

    /** 是否已因触及落盘上限而停止写盘。 */
    private boolean spillCapped;

    /** 落盘写入器，未溢出时为 {@code null}。 */
    private ToolOutputStore.SpillWriter writer;

    /** {@link #finish()} 的结果缓存，保证幂等。 */
    private String finished;

    /**
     * 构造 sink。
     *
     * @param store        落盘存储，不可为 {@code null}
     * @param sessionId    会话标识，可为 {@code null}
     * @param toolCallId   工具调用标识，可为 {@code null}
     * @param toolName     工具名，可为 {@code null}
     * @param maxChars     回灌字符预算，保证为正
     * @param spillMaxBytes 单个结果的落盘字节上限，{@code 0} 表示不限制
     * @param onChunk      实时输出的旁路接收者，可为 {@code null}
     */
    SpillCapturingSink(ToolOutputStore store, String sessionId, String toolCallId, String toolName, int maxChars,
                       long spillMaxBytes, Consumer<String> onChunk) {
        this.store = store;
        this.sessionId = sessionId;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.maxChars = Math.max(1, maxChars);
        this.spillMaxBytes = spillMaxBytes;
        this.onChunk = onChunk;
    }

    @Override
    public void write(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        tee(chunk);
        synchronized (lock) {
            if (finished != null) {
                // 收尾之后不应再有写入：工具已经交出了结果文本，再来一段会变成「谁也不知道它的内容」
                LOG.warn("输出捕获已收尾，丢弃迟到片段: tool={} chars={}", toolName, chunk.length());
                return;
            }
            bodyChars += chunk.length();
            bodyNewlines += countNewlines(chunk);
            if (!spilled) {
                buffered.append(chunk);
                if (buffered.length() > maxChars) {
                    spill();
                }
                return;
            }
            appendToWindows(chunk);
            writeToDisk(chunk);
        }
    }

    @Override
    public void summary(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        synchronized (lock) {
            if (finished != null) {
                return;
            }
            if (summary.length() > 0) {
                summary.append(" · ");
            }
            summary.append(text);
        }
    }

    @Override
    public String finish() {
        synchronized (lock) {
            if (finished != null) {
                return finished.isEmpty() ? null : finished;
            }
            if (bodyChars == 0 && summary.length() == 0) {
                finished = "";
                return null;
            }
            String text = spilled ? renderEnvelope() : renderPlain();
            finished = text == null ? "" : text;
            return finished.isEmpty() ? null : finished;
        }
    }

    /**
     * 未溢出：元数据首行 + 全部输出，不产生任何文件。
     *
     * @return 最终文本
     */
    private String renderPlain() {
        return joinSummary(buffered.toString());
    }

    /**
     * 溢出：元数据首行 + 头尾预览的信封。
     * <p>
     * <b>元数据行必须出现在预览开头</b>：信封会把整份正文换成预览，若只把元数据算进总量，
     * 模型就再也看不到退出码与工作目录了——而那正是这条命令里最重要的两个数字。因此头窗口不是
     * 从正文第 0 个字符开始，而是从「元数据行 + 正文」的第 0 个字符开始。
     *
     * @return 最终文本
     */
    private String renderEnvelope() {
        String path = writer == null ? null : writer.commit();
        boolean partial = spillCapped && path != null;
        String hint = ToolOutputEnvelope.hint(path, partial);
        int totalChars = summary.length() + (int) bodyChars;
        int totalLines = totalLines(summary.length(), bodyChars, bodyNewlines);
        String prefix = summary.length() == 0 ? "" : summary + "\n";
        int budget = ToolOutputEnvelope.previewBudget(maxChars, toolName, path, hint);
        String rendered = "";
        for (int round = 0; round < MAX_FIT_ROUNDS; round++) {
            int body = ToolOutputPreview.bodyBudget(budget);
            int headRoom = Math.max(0, ToolOutputPreview.headBudget(body) - prefix.length());
            String headPart = ToolOutputPreview.head(head.toString(), headRoom);
            String tailPart = ToolOutputPreview.tail(tail.toString(), ToolOutputPreview.tailBudget(body));
            long omittedChars = bodyChars - headPart.length() - tailPart.length();
            long omittedLines = bodyNewlines - countNewlines(headPart) - countNewlines(tailPart);
            String preview = ToolOutputPreview.join(prefix + headPart, tailPart, Math.max(0L, omittedLines),
                    Math.max(0L, omittedChars), budget);
            ToolOutputEnvelope envelope = ToolOutputEnvelope.text(toolName, totalChars, totalLines, path, hint,
                    preview, partial);
            rendered = envelope.render();
            int excess = rendered.length() - maxChars;
            if (excess <= 0 || budget <= 1) {
                return rendered;
            }
            budget = Math.max(1, budget - excess - ToolOutputEnvelope.FIT_SLACK);
        }
        return rendered;
    }

    /**
     * 计算「元数据行 + 正文」的行数。
     *
     * @param summaryChars   元数据行字符数
     * @param bodyChars      正文字符数
     * @param bodyNewlines   正文换行数
     * @return 行数
     */
    private static int totalLines(int summaryChars, long bodyChars, long bodyNewlines) {
        int lines = summaryChars > 0 ? 1 : 0;
        if (bodyChars > 0) {
            lines += 1 + (int) bodyNewlines;
        }
        return lines;
    }

    /**
     * 把元数据行与正文拼成最终文本。
     *
     * @param body 正文
     * @return 拼接结果
     */
    private String joinSummary(String body) {
        if (summary.length() == 0) {
            return body;
        }
        if (body.isEmpty()) {
            return summary.toString();
        }
        return summary + "\n" + body;
    }

    /**
     * 转入溢写期：开文件、把已缓冲的内容整体落盘并放进头尾窗口。
     */
    private void spill() {
        String content = buffered.toString();
        buffered = null;
        spilled = true;
        writer = store.open(sessionId, toolCallId, toolName);
        appendToWindows(content);
        writeToDisk(content);
    }

    /**
     * 把一段文本放进头尾窗口：头只留前一段，尾摊大到两倍预算后就裁掉前侧。
     *
     * @param chunk 文本片段
     */
    private void appendToWindows(String chunk) {
        int headLimit = ToolOutputPreview.headBudget(maxChars);
        if (head.length() < headLimit) {
            int room = headLimit - head.length();
            head.append(chunk, 0, Math.min(room, chunk.length()));
        }
        int tailLimit = ToolOutputPreview.tailBudget(maxChars);
        tail.append(chunk);
        // 摊大法：攒到两倍预算再裁一次，摊还下来每字符仍是 O(1)，不必实现真正的环形缓冲
        if (tail.length() > tailLimit * 2) {
            tail.delete(0, tail.length() - tailLimit);
        }
    }

    /**
     * 把一段文本写入落盘文件；触及上限时只写入装得下的那一段，之后停止写盘。
     * <p>
     * <b>为什么要写入「装得下的那一段」而不是直接停止</b>：落盘上限可能小于第一段缓冲，
     * 遇到就整个放弃会留下一个空文件，而信封却告诉模型「文件里有前面一部分」——那是另一句假话。
     * 写入截到上限为止，让「路径下确实有前一段内容」始终成立。
     *
     * @param chunk 文本片段
     */
    private void writeToDisk(String chunk) {
        if (spillCapped || writer == null) {
            return;
        }
        if (spillMaxBytes <= 0) {
            writer.write(chunk);
            return;
        }
        long room = spillMaxBytes - writer.getBytesWritten();
        if (room <= 0) {
            capSpill();
            return;
        }
        if (utf8Length(chunk) <= room) {
            writer.write(chunk);
            return;
        }
        writer.write(cutToBytes(chunk, room));
        capSpill();
    }

    /**
     * 标记落盘已达上限并记一次告警。
     * <p>
     * 后续内容仍会被继续读取（不读会让子进程因管道写满而阻塞）与计数，只是不再保存；
     * 信封会把「超出部分未捕获」如实写出来。
     */
    private void capSpill() {
        if (!spillCapped) {
            spillCapped = true;
            LOG.warn("工具输出超过落盘上限，后续内容不再保存: tool={} limit={} bytes", toolName,
                    spillMaxBytes);
        }
    }

    /**
     * 计算文本的 UTF-8 字节数。
     *
     * @param text 文本
     * @return 字节数
     */
    private static long utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 把文本按 UTF-8 字节上限截断，切口落在码点边界上。
     *
     * @param text     文本
     * @param maxBytes 字节上限
     * @return 截断后的文本
     */
    private static String cutToBytes(String text, long maxBytes) {
        long bytes = 0L;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            long next = bytes + utf8Length(new String(Character.toChars(codePoint)));
            if (next > maxBytes) {
                break;
            }
            bytes = next;
            index += Character.charCount(codePoint);
        }
        return text.substring(0, index);
    }

    /**
     * 把片段转给实时输出通道。
     * <p>
     * <b>旁路，可丢</b>：看一眼进度不该影响捕获。接收者抛错只记日志——它与捕获是两种不同的保证
     * （显示可以少几行，落盘与回灌不能少），因此绝不能让它的故障反过来打断写入。
     *
     * @param chunk 文本片段
     */
    private void tee(String chunk) {
        if (onChunk == null) {
            return;
        }
        try {
            onChunk.accept(chunk);
        } catch (RuntimeException e) {
            LOG.debug("实时输出转发失败，已忽略: tool={}", toolName, e);
        }
    }

    /**
     * 统计文本里的换行数。
     *
     * @param text 文本
     * @return 换行个数
     */
    private static long countNewlines(String text) {
        long lines = 0L;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}
