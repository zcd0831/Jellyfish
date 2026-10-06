package zcd.jellyfish.infra.llm;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.ProviderContribution;
import zcd.jellyfish.api.llm.LlmTransport;
import zcd.jellyfish.api.llm.LlmTransportListener;
import zcd.jellyfish.api.llm.LlmTransportMessage;
import zcd.jellyfish.api.llm.LlmTransportRequest;
import zcd.jellyfish.api.llm.LlmTransportResponse;
import zcd.jellyfish.api.llm.LlmTransportTool;
import zcd.jellyfish.api.llm.LlmTransportToolCall;
import zcd.jellyfish.api.llm.LlmTransportUsage;
import zcd.jellyfish.infra.config.Provider;
import zcd.jellyfish.infra.support.CancellationTokenSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把一个插件提供的 {@link LlmTransport} 适配成内核认识的 {@link LlmClient}。
 * <p>
 * <b>为什么需要这一层</b>：插件实现的是「厂商无关请求 → 厂商格式的 HTTP」（{@code api} 侧的稳定契约），
 * 而内核各处调用的是 {@link LlmClient}（{@code infra} 侧、随厂商演化的模型）。契约不进入内核实现，
 * 转换就必然发生在某一处；放在这里的好处是：<b>它是唯一一处</b>，两个世界都不必知道对方的类型。
 * <p>
 * <b>一次 {@code send} 同时服务同步与流式</b>：{@link #chat} 在调用方线程上内联执行并把事件聚合成结果，
 * {@link #chatStream} 把它交给流式线程池并原样转发事件。因此插件只实现一次传输，
 * 就自动有两条调用路径，不存在「同步能跑、流式不对」这种分裂。
 * <p>
 * <b>取消是「停投递 + 通知插件」两件事</b>：{@link #chatStream} 返回的句柄触发
 * {@link CancellationTokenSource}；令牌既让插件能登记「中断自家 HTTP 调用」的动作，
 * 也让本适配器在终止之后不再往下游转发事件——一个取消之后仍在回吐增量的插件，
 * 不能把内容冲进已经收尾的回合。
 * <p>
 * <b>插件没报终止事件时按失败处理</b>：传输契约要求 {@code send} 返回前已投递终止事件。真发生了违约，
 * 如实报一条错误，而不是把「没有响应」当成「空响应」静默继续——后者会让用户看到一次莫名其妙的空回合。
 * <p>
 * 线程安全：实例本身无状态，状态全在每次调用的局部量里，因此同一个实例可被多会话并发使用。
 *
 * @author zcd
 */
public final class PluginLlmClientAdapter implements LlmClient {

    /** 当前客户端绑定的 provider。 */
    private final Provider provider;

    /** 插件提供的传输实现。 */
    private final LlmTransport transport;

    /** 插件展示名，仅用于日志与报错归因。 */
    private final String displayName;

    /** 流式调用使用的线程池。 */
    private final ExecutorService streamExecutor;

    /**
     * 构造适配器。
     *
     * @param provider       provider 配置，不可为 {@code null}
     * @param contribution   插件交回的贡献，必须是「接管」的那种
     * @param streamExecutor 流式调用线程池，不可为 {@code null}
     * @throws JellyfishException 参数缺失或贡献声明为不接管时抛出
     */
    public PluginLlmClientAdapter(Provider provider, ProviderContribution contribution,
                                  ExecutorService streamExecutor) {
        if (provider == null) {
            throw new JellyfishException("provider must not be null");
        }
        if (contribution == null || !contribution.isSupported() || contribution.getTransport() == null) {
            throw new JellyfishException("provider contribution must be a supported one");
        }
        if (streamExecutor == null) {
            throw new JellyfishException("streamExecutor must not be null");
        }
        this.provider = provider;
        this.transport = contribution.getTransport();
        this.displayName = contribution.getDisplayName();
        this.streamExecutor = streamExecutor;
    }

    @Override
    public Provider getProvider() {
        return provider;
    }

    /**
     * 同步调用：内联执行传输，把事件聚合成一次完整响应。
     * <p>
     * <b>传给插件的是「不取消」的令牌</b>：这是内核既有的同步调用语义（压缩、缓存保活都走它），
     * 它们没有外部取消入口，凭空造一个没人触发的令牌只是噪音。
     *
     * @param request 统一请求模型
     * @return 聚合后的响应
     * @throws JellyfishException 传输抛错、或插件未投递终止事件时抛出
     */
    @Override
    public LlmResponse chat(LlmRequest request) {
        AggregatingListener listener = new AggregatingListener(describe());
        try {
            transport.send(toTransportRequest(request, null), listener);
        } catch (JellyfishException e) {
            // 包含插件带回的 LlmHttpException：状态码等语义原样保留，再包一层就没了
            throw e;
        } catch (RuntimeException e) {
            // 内核只认 JellyfishException（调用点靠它上报 LlmCallFailedEvent），
            // 插件抛的其它异常在这里归一化，不然一次失败会静默地少一条事件
            throw new JellyfishException("llm transport failed for provider: " + describe(), e);
        }
        return listener.awaitResult();
    }

    /**
     * 流式调用：把传输放到线程池上执行，事件原样转发给监听器。
     * <p>
     * <b>为什么不在调用方线程上跑</b>：调用方紧接着要等结果，若在这里阻塞，ReAct 循环就再没有机会
     * 处理取消——它会卡在「等插件返回」与「处理 Esc」之间的死结上。这与内置客户端的做法一致。
     * <p>
     * 线程池拒绝任务时按同步失败处理（投递 {@link LlmStreamListener#onError}），而不是让异常从
     * {@code chatStream} 里穿出去：调用点已经按「流式失败走监听器」写好了收尾。
     *
     * @param request  统一请求模型
     * @param listener 流式响应监听器
     * @return 可用于取消本次请求的句柄
     */
    @Override
    public LlmStreamHandle chatStream(LlmRequest request, LlmStreamListener listener) {
        CancellationTokenSource cancellation = new CancellationTokenSource();
        LlmStreamListener target = listener == null ? new LlmStreamListener() {
        } : listener;
        AtomicBoolean terminated = new AtomicBoolean(false);
        LlmTransportListener bridge = new ForwardingListener(target, terminated, describe());
        try {
            streamExecutor.execute(new Runnable() {

                @Override
                public void run() {
                    try {
                        transport.send(toTransportRequest(request, cancellation), bridge);
                        // 插件违约（没投终止事件）时在此如实报错：调用点靠终止事件收尾，
                        // 少一个就是一次永久挂住的回合
                        if (terminated.compareAndSet(false, true)) {
                            target.onError(new JellyfishException(
                                    "llm transport returned without a terminal event for provider: "
                                            + describe()));
                        }
                    } catch (RuntimeException e) {
                        // 插件抛出的异常（含它带回的状态码）原样交给监听器，不再包一层：
                        // 订阅方要靠类型拿状态码区分「降级」与「重试」
                        if (terminated.compareAndSet(false, true)) {
                            target.onError(e);
                        }
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            terminated.set(true);
            target.onError(new JellyfishException("llm stream executor rejected task for provider: "
                    + provider.getName(), e));
        }
        return new LlmStreamHandle() {

            @Override
            public void cancel() {
                cancellation.cancel();
            }
        };
    }

    /**
     * 把内核请求翻译成传输层请求。
     *
     * @param request    内核请求
     * @param cancellation 取消令牌，同步路径传 {@code null}
     * @return 传输层请求
     */
    private LlmTransportRequest toTransportRequest(LlmRequest request, CancellationTokenSource cancellation) {
        List<LlmTransportMessage> messages = new ArrayList<LlmTransportMessage>();
        for (LlmMessage message : request.getMessages()) {
            messages.add(toTransportMessage(message));
        }
        List<LlmTransportTool> tools = new ArrayList<LlmTransportTool>();
        for (LlmTool tool : request.getTools()) {
            tools.add(new LlmTransportTool(tool.getName(), tool.getDescription(),
                    tool.getParameters(), tool.getRequired()));
        }
        LlmTransportRequest.Builder builder = LlmTransportRequest
                .builder(normalizedType(), request.getModel())
                .providerName(provider.getName())
                .apiKey(provider.getApiKey())
                .baseUrl(provider.getBaseUrl())
                .systemPrompt(request.getSystemPrompt())
                .messages(messages)
                .tools(tools.isEmpty() ? null : tools)
                .toolChoice(request.getToolChoice())
                .temperature(request.getTemperature())
                .maxTokens(request.getMaxTokens())
                .maxTokensField(request.getMaxTokensField())
                .topP(request.getTopP())
                .topK(request.getTopK())
                .seed(request.getSeed())
                .frequencyPenalty(request.getFrequencyPenalty())
                .presencePenalty(request.getPresencePenalty())
                .stop(request.getStop().isEmpty() ? null : request.getStop())
                .cacheKey(request.getCacheKey())
                .cacheRetention(request.getCacheRetention())
                .cacheBreakpoints(request.getCacheBreakpoints())
                // 直通字段一并交出去：插件看不到它的话，用户会遇到「换了 type 之后配的 reasoning_effort
                // 就不再生效」。请求头只有 provider 级，因此从 provider 读（与内置客户端同一口径）
                .vendorBody(request.getVendorBody())
                .vendorHeaders(provider.getVendorHeaders());
        if (request.isMinimalOutput()) {
            builder.minimalOutput();
        }
        if (cancellation != null) {
            builder.cancellationToken(cancellation);
        }
        return builder.build();
    }

    /**
     * 把内核消息翻译成传输层消息。
     *
     * @param message 内核消息
     * @return 传输层消息
     */
    private static LlmTransportMessage toTransportMessage(LlmMessage message) {
        List<LlmTransportToolCall> toolCalls = null;
        if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
            toolCalls = new ArrayList<LlmTransportToolCall>();
            for (LlmToolCall call : message.getToolCalls()) {
                toolCalls.add(new LlmTransportToolCall(call.getIndex(), call.getId(),
                        call.getName(), call.getArguments()));
            }
        }
        return new LlmTransportMessage(message.getRole(), message.getContent(),
                null, toolCalls, message.getToolCallId(), message.getName());
    }

    /**
     * 取已规范化（去空白、小写）的 provider 类型，与注册表的键口径一致。
     *
     * @return 规范化后的类型
     */
    private String normalizedType() {
        String type = provider.getType();
        return type == null ? "" : type.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 描述本次调用归属的端点与插件，用于报错归因。
     * <p>
     * <b>两个名字都写</b>：用户写的是 provider 名，而排查「到底哪个插件出问题」靠的是展示名。
     * 只写其中一个，总会有一类问题需要去翻配置文件。
     *
     * @return 描述
     */
    private String describe() {
        return provider.getName() + "（" + displayName + "）";
    }

    /**
     * 同步调用的事件聚合器：把流式增量拼回一次完整响应。
     * <p>
     * <b>工具调用按 index 归并</b>：与内核既有的 {@code StreamToolCallAccumulator} 同一口径，
     * 但那一份在 {@code infra} 里依赖厂商分片细节，这里只需要「同一 index 覆盖、按出现顺序排列」。
     *
     * @author zcd
     */
    private static final class AggregatingListener implements LlmTransportListener {

        /** 端点描述，用于报错归因。 */
        private final String describe;

        /** 文本累积。 */
        private final StringBuilder text = new StringBuilder();

        /** 思考过程累积。 */
        private final StringBuilder reasoning = new StringBuilder();

        /** 工具调用，按 index 归并后的有序列表。 */
        private final List<LlmToolCall> toolCalls = new ArrayList<LlmToolCall>();

        /** 工具调用 index 到它在列表中的位置。 */
        private final List<Integer> toolCallIndexes = new ArrayList<Integer>();

        /** 终止事件是否已到。 */
        private boolean terminated;

        /** 最终结果。 */
        private LlmTransportResponse response;

        /** 失败原因。 */
        private Throwable error;

        /** 是否被取消。 */
        private boolean cancelled;

        /**
         * 构造聚合器。
         *
         * @param describe 端点描述
         */
        private AggregatingListener(String describe) {
            this.describe = describe;
        }

        @Override
        public void onText(String delta) {
            if (!terminated && delta != null) {
                text.append(delta);
            }
        }

        @Override
        public void onReasoning(String delta) {
            if (!terminated && delta != null) {
                reasoning.append(delta);
            }
        }

        @Override
        public void onToolCall(LlmTransportToolCall toolCall) {
            if (terminated || toolCall == null) {
                return;
            }
            LlmToolCall mapped = new LlmToolCall(toolCall.getIndex(), toolCall.getId(),
                    toolCall.getName(), toolCall.getArguments());
            int position = positionOf(toolCall.getIndex());
            if (position < 0) {
                toolCallIndexes.add(toolCall.getIndex());
                toolCalls.add(mapped);
            } else {
                toolCalls.set(position, mapped);
            }
        }

        @Override
        public void onComplete(LlmTransportResponse result) {
            terminated = true;
            response = result;
        }

        @Override
        public void onCancelled() {
            terminated = true;
            cancelled = true;
        }

        @Override
        public void onError(Throwable failure) {
            terminated = true;
            error = failure;
        }

        /**
         * 取回本次调用的结果。
         *
         * @return 统一响应
         * @throws JellyfishException 插件报错、取消、或未投递终止事件时抛出
         */
        private LlmResponse awaitResult() {
            if (error != null) {
                if (error instanceof JellyfishException) {
                    throw (JellyfishException) error;
                }
                throw new JellyfishException("llm transport failed for provider: " + describe, error);
            }
            if (cancelled) {
                throw new JellyfishException("llm transport cancelled for provider: " + describe);
            }
            if (!terminated) {
                throw new JellyfishException("llm transport returned without a terminal event for provider: "
                        + describe);
            }
            String content = text.length() == 0 ? (response == null ? null : response.getContent()) : text.toString();
            List<LlmToolCall> calls = toolCalls.isEmpty()
                    ? (response == null ? null : toToolCalls(response.getToolCalls()))
                    : toolCalls;
            String thinking = reasoning.length() == 0
                    ? (response == null ? null : response.getReasoning())
                    : reasoning.toString();
            return new LlmResponse(content, thinking, calls,
                    response == null ? null : toUsage(response.getUsage()),
                    response == null ? null : response.getFinishReason());
        }

        /**
         * 查找某个 index 已在列表中的位置。
         * <p>
         * {@code index} 为空时恒返回「未出现」：非流式的端点不会下发分片归属，
         * 而把多个调用归并成同一个才是真正的错。
         *
         * @param index 分片标识
         * @return 位置，未出现过时返回 {@code -1}
         */
        private int positionOf(Integer index) {
            if (index == null) {
                return -1;
            }
            for (int i = 0; i < toolCallIndexes.size(); i++) {
                if (index.equals(toolCallIndexes.get(i))) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * 把传输层工具调用整体转成内核类型。
         *
         * @param calls 传输层工具调用
         * @return 内核工具调用，输入为 {@code null} 时返回 {@code null}
         */
        private static List<LlmToolCall> toToolCalls(List<LlmTransportToolCall> calls) {
            if (calls == null || calls.isEmpty()) {
                return null;
            }
            List<LlmToolCall> result = new ArrayList<LlmToolCall>();
            for (LlmTransportToolCall call : calls) {
                result.add(new LlmToolCall(call.getIndex(), call.getId(), call.getName(),
                        call.getArguments()));
            }
            return result;
        }

        /**
         * 把传输层用量转成内核用量。
         *
         * @param usage 传输层用量
         * @return 内核用量，输入为 {@code null} 时返回 {@code null}
         */
        private static LlmUsage toUsage(LlmTransportUsage usage) {
            if (usage == null) {
                return null;
            }
            return new LlmUsage(usage.getPromptTokens(), usage.getCompletionTokens(),
                    usage.getTotalTokens(), usage.getCacheReadTokens(), usage.getCacheWriteTokens());
        }
    }

    /**
     * 流式调用的转发器：逐事件转发给内核监听器，并在终止之后停止投递。
     *
     * @author zcd
     */
    private static final class ForwardingListener implements LlmTransportListener {

        /** 内核监听器。 */
        private final LlmStreamListener target;

        /** 终止标志，由调用方与终态事件共同维护。 */
        private final AtomicBoolean terminated;

        /** 插件展示名，用于报错归因。 */
        private final String describe;

        /**
         * 构造转发器。
         *
         * @param target     内核监听器
         * @param terminated 终止标志
         * @param describe   端点描述
         */
        private ForwardingListener(LlmStreamListener target, AtomicBoolean terminated, String describe) {
            this.target = target;
            this.terminated = terminated;
            this.describe = describe;
        }

        @Override
        public void onOpen() {
            if (!terminated.get()) {
                target.onOpen();
            }
        }

        @Override
        public void onText(String delta) {
            if (!terminated.get()) {
                target.onText(delta);
            }
        }

        @Override
        public void onReasoning(String delta) {
            if (!terminated.get()) {
                target.onThinking(delta);
            }
        }

        @Override
        public void onToolCall(LlmTransportToolCall toolCall) {
            if (!terminated.get() && toolCall != null) {
                target.onToolCall(new LlmToolCall(toolCall.getIndex(), toolCall.getId(),
                        toolCall.getName(), toolCall.getArguments()));
            }
        }

        @Override
        public void onComplete(LlmTransportResponse response) {
            if (terminated.compareAndSet(false, true)) {
                target.onComplete(toResponse(response));
            }
        }

        @Override
        public void onCancelled() {
            if (terminated.compareAndSet(false, true)) {
                target.onCancelled();
            }
        }

        @Override
        public void onError(Throwable error) {
            if (terminated.compareAndSet(false, true)) {
                target.onError(error);
            }
        }

        /**
         * 把传输层结果转成内核结果。
         *
         * @param response 传输层结果
         * @return 内核结果
         */
        private LlmResponse toResponse(LlmTransportResponse response) {
            if (response == null) {
                return new LlmResponse(null, null, null, null, null);
            }
            return new LlmResponse(response.getContent(), response.getReasoning(),
                    AggregatingListener.toToolCalls(response.getToolCalls()),
                    AggregatingListener.toUsage(response.getUsage()), response.getFinishReason());
        }
    }
}
