package zcd.jellyfish.script;

/**
 * 承载脚本协议的常驻子进程（本方案里就是「网关」进程）。
 * <p>
 * <b>它只管字节与生命周期，不认协议</b>：往里写一行文本、报告自己是否还活着、按两段式关闭。
 * id 配对与超时在 {@code protocol.ScriptRpc}，业务语义在 {@code ScriptGateway}。
 * 这么切的理由是：进程管理是与操作系统打交道（管道、信号、僵尸回收），协议是与脚本打交道，
 * 两者的失效方式与排查手段完全不同，混在一起会让「进程没起来」和「脚本答错了」看起来一样。
 * <p>
 * <b>{@link #send(String)} 抛异常是 L3 信号</b>：写不进去只有一种解释——进程没了或管道断了。
 * 实现方不要在这里吞异常或重试：调用方需要立刻知道「整门语言不可用」，
 * 而不是等到某次调用的超时才发现。
 * <p>
 * <b>关闭是两段式的</b>：先请它自己走（{@code destroy}，给它机会杀干净自己的 worker），
 * 到时间再强杀。只做前者会让卡死的心跳进程永远留着；只做后者会让 worker 变成孤儿
 * （这正是「网关是 worker 的父进程」这一设计要保护的东西）。
 *
 * @author zcd
 */
public interface ScriptProcess {

    /**
     * 发送一行协议文本。
     * <p>
     * 实现方必须保证整行原子：多个调用线程并发发送时，两行内容不能交错。
     * 文本不含结尾换行，由实现方补。
     *
     * @param line 一帧协议文本，不可为 {@code null}
     * @throws zcd.jellyfish.api.JellyfishException 进程不可用或写入失败时抛出
     */
    void send(String line);

    /**
     * 判断进程是否仍在运行。
     *
     * @return 仍在运行返回 {@code true}
     */
    boolean isAlive();

    /**
     * 关闭进程。
     * <p>
     * 幂等，可在任意线程调用。实现方应先请进程自己退出、超时后再强杀，
     * 并保证返回时不会再留下自己的读取线程。
     *
     * @param graceMillis 优雅退出等待毫秒数
     * @param killMillis  强杀后等待毫秒数
     */
    void close(long graceMillis, long killMillis);
}
