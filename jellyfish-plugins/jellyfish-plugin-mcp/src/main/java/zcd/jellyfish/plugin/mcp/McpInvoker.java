package zcd.jellyfish.plugin.mcp;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CancellationToken;

import java.util.Map;

/**
 * 工具调用的发起方接口：把「工具处理器」与「怎么把消息送到对面」分开。
 * <p>
 * <b>为什么需要这道缝</b>：工具处理器要做的是「把结果映射成内核的工具结果」（文本 + 元数据 + 失败标记），
 * 而连接对象要做的是「按 id 配对、按超时等待、按取消令牌中止」。两者合在一起就变成
 * 一个既要知道 JSON-RPC 又要知道 {ToolMetadata} 约定的类，而其中只有一半能单测。
 * <p>
 * 实现必须线程安全：工具调用跑在 {@code react} 线程上，可能同时有多个。
 *
 * @author zcd
 */
interface McpInvoker {

    /**
     * 调用一个工具。
     *
     * @param originalToolName  server 认得的原始工具名，不可为空白
     * @param arguments         工具参数，可为 {@code null}
     * @param timeoutMillis     超时毫秒数；{@code <= 0} 表示不超时
     * @param cancellationToken 取消令牌，可为 {@code null}
     * @return 调用结果，保证非 {@code null}
     * @throws JellyfishException 连接不可用、协议错误、超时或取消时抛出
     */
    McpCallOutcome callTool(String originalToolName, Map<String, Object> arguments, long timeoutMillis,
                            CancellationToken cancellationToken);

    /**
     * 一次成功送达的工具调用结果。
     * <p>
     * <b>协议层的「工具报告失败」不是异常</b>：{@code isError} 是 server 明确答复的一种正常结果
     * （命令跑了但没成），而超时、进程死掉、JSON 非法才是调用本身失败。把前者也做成异常会让
     * 「命令说了没有」与「命令根本没跑起来」在内核侧变成同一件事。
     */
    final class McpCallOutcome {

        /** 已渲染的文本。 */
        private final String text;

        /** server 是否声明这次调用失败。 */
        private final boolean error;

        /** 往返耗时毫秒数。 */
        private final long durationMillis;

        /**
         * 构造结果。
         *
         * @param text           已渲染的文本
         * @param error          server 是否声明失败
         * @param durationMillis 往返耗时毫秒数
         */
        McpCallOutcome(String text, boolean error, long durationMillis) {
            this.text = text == null ? "" : text;
            this.error = error;
            this.durationMillis = durationMillis;
        }

        /**
         * 获取已渲染的文本。
         *
         * @return 文本，保证非 {@code null}
         */
        String text() {
            return text;
        }

        /**
         * 判断 server 是否声明这次调用失败。
         *
         * @return 失败返回 {@code true}
         */
        boolean error() {
            return error;
        }

        /**
         * 获取往返耗时。
         *
         * @return 毫秒数
         */
        long durationMillis() {
            return durationMillis;
        }
    }
}
