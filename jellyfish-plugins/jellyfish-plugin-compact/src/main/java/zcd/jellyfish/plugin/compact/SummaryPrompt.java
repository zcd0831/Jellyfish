package zcd.jellyfish.plugin.compact;

import zcd.jellyfish.api.JellyfishException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 摘要指令来源：读本插件自带的资源文件。
 * <p>
 * <b>为什么不复用内核的 {@code SettingsReader}</b>：那是 {@code jellyfish-infra} 的类，而插件对
 * {@code jellyfish-infra} 没有依赖（插件只依赖 {@code jellyfish-api}，且是 provided）。插件读自己的
 * 资源只有一条正路——用自己的类加载器，从自己的 jar 里读。这也正是「资源跟着读者走」的具体形态。
 * <p>
 * <b>为什么在启动期一次性读完</b>：它是不变的内容，而 {@code CompactionStrategyRequest} 每次压缩都会
 * 被问到（自动压缩下每轮都可能问）；把 I/O 留在启动期，处理器就永远是纯粹的内存操作——这既是性能，
 * 也是插件处理器的硬约束（调用点在同步派发路径上）。
 * <p>
 * <b>缺文件直接抛</b>：摘要指令是压缩功能的全部内容，没有它这个插件就等于没装；启动期就炸比等到用户
 * 敲 {@code /compact} 时才发现要好得多。
     * <p>
     * <b>为什么资源放在 jar 根目录而不是与本包同名路径</b>：这份文件是给人看、给人改的（摘要措辞、输出
     * 结构、长度约束都写在这里），<b>它不是类路径上要按包名精确匹配的数据</b>，也不担心与别的 jar 撞名——
     * 每个插件是独立 jar，根目录只有它自己那几份文件。跟着包名埋到 {@code zcd/jellyfish/plugin/compact/}
     * 底下，只会让「想改摘要措辞的人」多挖三层目录。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class SummaryPrompt {

    /** 摘要指令资源路径：放在资源根目录，不与本包的包名镜像。 */
    static final String RESOURCE_PATH = "summary-prompt.md";

    /** 单次读取的资源上限：1MB，防止误指向一个巨大的文件把内存吃满。 */
    private static final int MAX_BYTES = 1024 * 1024;

    /** 摘要指令正文。 */
    private final String text;

    /**
     * 构造摘要指令来源。
     *
     * @param text 指令正文，保证非空白
     */
    private SummaryPrompt(String text) {
        this.text = text;
    }

    /**
     * 从本插件的 jar 里装载摘要指令。
     *
     * @return 摘要指令，保证正文非空白
     * @throws JellyfishException 资源缺失、读取失败或内容为空时抛出
     */
    static SummaryPrompt load() {
        return loadFrom(RESOURCE_PATH);
    }

    /**
     * 从指定资源路径装载摘要指令。
     * <p>
     * 路径作为参数而不是写死在方法里，是为了让「资源缺失时直接抛」这条失败语义可测：
     * 真正的资源路径是个常量，测试没办办法让它不存在。
     *
     * @param path 资源路径，不可为空白
     * @return 摘要指令，保证正文非空白
     * @throws JellyfishException 资源缺失、读取失败或内容为空时抛出
     */
    static SummaryPrompt loadFrom(String path) {
        ClassLoader loader = SummaryPrompt.class.getClassLoader();
        InputStream stream = loader.getResourceAsStream(path);
        if (stream == null) {
            throw new JellyfishException("压缩插件缺少摘要指令资源: " + path);
        }
        String text = read(stream, path).trim();
        if (text.isEmpty()) {
            throw new JellyfishException("压缩插件的摘要指令资源为空: " + path);
        }
        return new SummaryPrompt(text);
    }

    /**
     * 读取资源内容。
     *
     * @param stream 资源流，由本方法负责关闭
     * @param path   资源路径，仅用于错误信息
     * @return 文本内容
     * @throws JellyfishException 读取失败或内容超过上限时抛出
     */
    private static String read(InputStream stream, String path) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        try {
            int read = stream.read(chunk);
            while (read >= 0) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > MAX_BYTES) {
                    throw new JellyfishException("摘要指令资源过大（超过 " + MAX_BYTES + " 字节）: " + path);
                }
                read = stream.read(chunk);
            }
        } catch (IOException e) {
            throw new JellyfishException("读取摘要指令资源失败: " + path, e);
        } finally {
            closeQuietly(stream);
        }
        // 资源文件是 UTF-8（中文指令），不能靠平台默认编码
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * 静默关闭流：关闭失败不影响已经读到的内容。
     *
     * @param stream 资源流
     */
    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException e) {
            // 资源流关闭失败无法补救，也不该让插件启动失败
        }
    }

    /**
     * 获取摘要指令正文。
     *
     * @return 指令正文，保证非空白
     */
    String text() {
        return text;
    }
}
