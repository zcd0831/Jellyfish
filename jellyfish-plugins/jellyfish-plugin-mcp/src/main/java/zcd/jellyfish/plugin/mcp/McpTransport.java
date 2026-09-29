package zcd.jellyfish.plugin.mcp;

import java.io.IOException;

/**
 * MCP 的传输抽象：一条能把消息送出去、把消息读回来的通道。
 * <p>
 * <b>为什么需要一个接口</b>：连接对象里装的几乎全是协议逻辑（按 id 配对、超时摘等待位、
 * 通知分发、能力回绝），而这些逻辑与「消息其实是从子进程的管道里来的」没有关系。
 * 把它们和 {@code ProcessBuilder} 绑在一个类里，结果是协议逻辑只能靠起真进程来测，
 * 而那种用例既慢又难构造（握手超时、乱序应答、server 主动请求都很难凑齐）。
 * <p>
 * <b>单实现不是过度设计</b>：生产实现只有 stdio 一个，但测试需要一个内存实现，
 * 而「只有一个实现的接口」与「为了测试才存在的接缝」是两件事——后者恰恰是本项目
 * 在脚本运行时（{@code ScriptProcess} / {@code ScriptProcessFactory}）已经用过的手法。
 *
 * @author zcd
 */
interface McpTransport extends AutoCloseable {

    /**
     * 写一条消息。
     *
     * @param json 单行 JSON 文本，不可为 {@code null}
     * @throws IOException 写入失败时抛出
     */
    void send(String json) throws IOException;

    /**
     * 阻塞读取一条消息。
     *
     * @return 一条消息文本；通道关闭时返回 {@code null}
     * @throws IOException 读取失败时抛出
     */
    String readLine() throws IOException;

    /**
     * 判断通道是否仍然可用。
     *
     * @return 可用返回 {@code true}
     */
    boolean isAlive();

    @Override
    void close();

    /**
     * 传输工厂：把「怎么起一条通道」与「通道上跑什么协议」分开。
     */
    interface Factory {

        /**
         * 起一条通道。
         *
         * @param config 服务配置，不可为 {@code null}
         * @return 通道，保证非 {@code null}
         * @throws IOException 启动失败时抛出
         */
        McpTransport start(McpServerConfig config) throws IOException;
    }
}
