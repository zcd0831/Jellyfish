package zcd.jellyfish.plugin.shell;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import zcd.jellyfish.api.extension.ToolOutputSink;

/**
 * 把子进程的合并输出解码成文本写进内核的捕获通道。
 * <p>
 * <b>为什么需要一个适配流而不是直接写 sink</b>：泵线程给的是字节，而 sink 要的是字符，
 * 两者之间有三件必须做的事：
 * <ol>
 *     <li><b>跨块解码</b>：一个 UTF-8 汉字可能被切成两块。用有状态的 {@link CharsetDecoder}
 *     累积残余字节，而不是每块各自 {@code new String(bytes)}——后者会把中文日志撕成乱码，
 *     而那些中文日志恰恰是排查时最需要看清的部分；</li>
 *     <li><b>二进制识别</b>：命令可能产出图片、压缩包、可执行文件。把二进制灌进上下文既没有价值，
 *     又会污染后续所有轮次，因此识别到之后只计数、不再写入；</li>
 *     <li><b>持续 drain</b>：无论是否保留内容，都必须一直读。停止读取会让子进程因管道写满而永久阻塞，
 *     表现是「命令卡死」——这正是最难归因的一类故障。</li>
 * </ol>
 * <b>线程安全</b>：stdout 与 stderr 两条泵线程会并发调用，因此整个写入路径在同一把锁里。
 * <p>
 * <b>二进制判据</b>（刻意简单且可断言）：出现 NUL 字节即判定为二进制；或替换字符（U+FFFD）数量
 * 达到 4 个且占比超过 5%。文本里的替换字符极少见，而真正的二进制几乎必然触发其中一条。
 *
 * @author zcd
 */
final class ShellOutputCapture extends OutputStream {

    /** 判定为二进制所需的最少替换字符个数。 */
    private static final int REPLACEMENT_THRESHOLD = 4;

    /** 替换字符占比判据的分母比例（超过 5% 即二进制）。 */
    private static final int REPLACEMENT_RATIO = 20;

    /** 捕获通道。 */
    private final ToolOutputSink sink;

    /** 有状态解码器：跨调用保留未完成的字符序列。 */
    private final CharsetDecoder decoder;

    /** 解码结果的暂存缓冲。 */
    private final CharBuffer chars = CharBuffer.allocate(8192);

    /**
     * 上一块末尾「被切成两半」的那个字符的残余字节。
     * <p>
     * <b>为什么必须自己留着它</b>：{@code CharsetDecoder.decode(in, out, false)} 遇到不完整的
     * 多字节序列时会返回 UNDERFLOW 并把那几个字节<b>留在输入缓冲里</b>（不消费），
     * 指望调用方下次把它们和后续字节一起再喂进来。每块各写各的 {@code ByteBuffer} 就等于把
     * 那个半截字符丢掉——现象正是「中文日志偶尔出现一个乱码字符」。
     */
    private byte[] pending = new byte[0];

    /** 累计原始字节数。 */
    private long bytes;

    /** 累计写入 sink 的字符数。 */
    private long textChars;

    /** 累计出现的替换字符数。 */
    private int replacements;

    /** 是否已判定为二进制输出。 */
    private boolean binary;

    /** 最近一次收到输出的时刻，供静默超时判定。 */
    private volatile long lastOutputAt;

    /** 是否已收尾。 */
    private boolean closed;

    /**
     * 构造捕获流。
     *
     * @param sink 捕获通道，不可为 {@code null}
     */
    ShellOutputCapture(ToolOutputSink sink) {
        this.sink = sink;
        this.decoder = StandardCharsets.UTF_8.newDecoder()
                // 非法字节不抛异常：命令输出本来就可能不是合法 UTF-8，为它中断整次调用毫无意义
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        this.lastOutputAt = System.currentTimeMillis();
    }

    @Override
    public synchronized void write(int value) {
        byte[] single = {(byte) value};
        write(single, 0, 1);
    }

    @Override
    public synchronized void write(byte[] buffer, int offset, int length) {
        if (buffer == null || length <= 0 || closed) {
            return;
        }
        bytes += length;
        // 时刻先更新：静默超时判定的是「有没有输出」，与输出是不是二进制无关
        lastOutputAt = System.currentTimeMillis();
        if (containsNul(buffer, offset, length)) {
            markBinary();
        }
        if (binary) {
            return;
        }
        ByteBuffer input = ByteBuffer.wrap(concat(buffer, offset, length));
        boolean endOfInput = false;
        while (true) {
            CoderResult result = decoder.decode(input, chars, endOfInput);
            emit();
            if (!result.isOverflow()) {
                break;
            }
        }
        keepRemainder(input);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (binary) {
            return;
        }
        // 把残余字节按「输入到此结束」解码：一个被切断的字符在这里变成替换字符，
        // 而这正是「这里有个坏序列」的如实表达——丢掉它就是在隐瞒输出
        ByteBuffer input = ByteBuffer.wrap(concat(new byte[0], 0, 0));
        boolean endOfInput = true;
        while (true) {
            CoderResult result = decoder.decode(input, chars, endOfInput);
            emit();
            if (!result.isOverflow()) {
                break;
            }
        }
        decoder.flush(chars);
        emit();
    }

    /**
     * 把上一块的残余字节与这一块拼起来。
     *
     * @param buffer 本块字节
     * @param offset 起始下标
     * @param length 长度
     * @return 拼接结果，保证非 {@code null}
     */
    private byte[] concat(byte[] buffer, int offset, int length) {
        if (pending.length == 0) {
            byte[] copy = new byte[length];
            System.arraycopy(buffer, offset, copy, 0, length);
            return copy;
        }
        byte[] merged = new byte[pending.length + length];
        System.arraycopy(pending, 0, merged, 0, pending.length);
        System.arraycopy(buffer, offset, merged, pending.length, length);
        pending = new byte[0];
        return merged;
    }

    /**
     * 留下本块未被消费的字节。
     * <p>
     * 未被消费意味着「这是一个还没写完的字符」，必须等下一块（或收尾）再处理。
     *
     * @param input 刚解码过的输入缓冲
     */
    private void keepRemainder(ByteBuffer input) {
        if (!input.hasRemaining()) {
            return;
        }
        byte[] rest = new byte[input.remaining()];
        input.get(rest);
        pending = rest;
    }

    /**
     * 获取累计收到的原始字节数。
     *
     * @return 字节数
     */
    synchronized long bytes() {
        return bytes;
    }

    /**
     * 判断输出是否被判定为二进制。
     *
     * @return 判定为二进制返回 {@code true}
     */
    synchronized boolean isBinary() {
        return binary;
    }

    /**
     * 获取最近一次收到输出的时刻。
     *
     * @return 毫秒时间戳
     */
    long lastOutputAt() {
        return lastOutputAt;
    }

    /**
     * 把解码结果写进捕获通道。
     * <p>
     * 在锁内调用，因此不必自己同步；调用后缓冲被清空。
     */
    private void emit() {
        chars.flip();
        if (!chars.hasRemaining()) {
            chars.clear();
            return;
        }
        String text = chars.toString();
        chars.clear();
        textChars += text.length();
        replacements += countReplacements(text);
        // 占比判据只在文本已经有一定量之后才有意义：几个字符就下结论会把「开头恰好有个坏字节」误判
        if (replacements >= REPLACEMENT_THRESHOLD && replacements * REPLACEMENT_RATIO > textChars) {
            markBinary();
            return;
        }
        sink.write(text);
    }

    /**
     * 标记为二进制输出。
     * <p>
     * 只标记、不中断：后续字节继续被读取与计数（否则子进程会因为管道写满而卡住），
     * 只是不再进入上下文。已经写进去的那一小段文本留在里面——它在总输出里占比极小，
     * 而把它撤回来需要捕获通道支持回退，代价远大于收益。
     */
    private void markBinary() {
        binary = true;
    }

    /**
     * 判断字节块里是否含 NUL。
     *
     * @param buffer 字节数组
     * @param offset 起始下标
     * @param length 长度
     * @return 含 NUL 返回 {@code true}
     */
    private static boolean containsNul(byte[] buffer, int offset, int length) {
        for (int index = offset; index < offset + length; index++) {
            if (buffer[index] == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 统计文本里的替换字符个数。
     *
     * @param text 文本
     * @return 个数
     */
    private static int countReplacements(String text) {
        int count = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\uFFFD') {
                count++;
            }
        }
        return count;
    }
}
