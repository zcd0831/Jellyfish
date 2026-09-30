package zcd.jellyfish.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.LlmHttpException;
import zcd.jellyfish.infra.config.Provider;
import zcd.jellyfish.infra.support.LlmClients;
import zcd.jellyfish.infra.support.ObjectMapperWrapper;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 基于 OkHttp 的 LLM 客户端基类，负责：
 * <ul>
 *     <li>统一的 JSON 请求/响应处理与错误封装；</li>
 *     <li>手写 SSE（text/event-stream）解析与增量回调；</li>
 *     <li>流式请求的后台线程执行与 {@link Call#cancel()} 取消。</li>
 * </ul>
 *
 * @author zcd
 */
public abstract class AbstractHttpLlmClient implements LlmClient {

    /** 统一的 JSON 请求媒体类型。 */
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    /** 错误响应体写入异常信息时的截断长度，避免日志被超长响应淹没。 */
    private static final int ERROR_BODY_LIMIT = 2000;

    /** 当前客户端绑定的 provider 配置。 */
    protected final Provider provider;

    /** 共享的 HTTP 客户端，其连接池会被所有客户端复用。 */
    protected final OkHttpClient okHttpClient;

    /** 执行流式请求的线程池，线程为守护线程。 */
    private final ExecutorService streamExecutor;

    /**
     * 构造客户端基类。
     * <p>
     * 构造时校验 provider、HTTP 客户端、线程池与 apiKey，使配置错误在切换模型时即刻暴露，
     * 而不是等到首次调用才抛异常。
     *
     * @param provider        provider 配置，不可为空
     * @param okHttpClient    共享的 HTTP 客户端，不可为空
     * @param streamExecutor  流式请求线程池，不可为空
     * @throws JellyfishException 任一参数为 {@code null} 或 apiKey 缺失时抛出
     */
    protected AbstractHttpLlmClient(Provider provider, OkHttpClient okHttpClient, ExecutorService streamExecutor) {
        if (provider == null) {
            throw new JellyfishException("provider must not be null");
        }
        if (okHttpClient == null) {
            throw new JellyfishException("okHttpClient must not be null");
        }
        if (streamExecutor == null) {
            throw new JellyfishException("streamExecutor must not be null");
        }
        // 提前校验，配置错误在切换模型时即可暴露
        LlmClients.requireApiKey(provider);
        this.provider = provider;
        this.okHttpClient = okHttpClient;
        this.streamExecutor = streamExecutor;
    }

    /**
     * 获取当前客户端绑定的 provider。
     *
     * @return provider 配置
     */
    @Override
    public Provider getProvider() {
        return provider;
    }

    /**
     * 校验请求非空。各实现的 {@code chat} / {@code chatStream} 入口统一调用，避免重复校验。
     *
     * @param request 统一请求模型
     * @throws JellyfishException 请求为 {@code null} 时抛出
     */
    protected static void requireRequest(LlmRequest request) {
        if (request == null) {
            throw new JellyfishException("request must not be null");
        }
    }

    /**
     * 构造一个带 JSON 请求头的请求构建器。
     *
     * @param url 请求地址
     * @return 请求构建器
     */
    protected Request.Builder jsonRequest(String url) {
        return new Request.Builder()
                .url(url)
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .header("Accept", "application/json");
    }

    /**
     * 将对象序列化为 JSON 请求体。
     *
     * @param value 待序列化对象
     * @return 请求体
     */
    protected RequestBody jsonBody(Object value) {
        return RequestBody.create(ObjectMapperWrapper.writeValueAsString(value), JSON_MEDIA_TYPE);
    }

    /**
     * 同步执行请求并把响应体反序列化为指定类型。
     *
     * @param request 待执行的请求
     * @param type    响应体目标类型
     * @param action  动作名，用于拼接异常信息
     * @param <T>     响应体目标类型
     * @return 反序列化结果
     * @throws JellyfishException HTTP 非 2xx、响应体为空、反序列化失败或网络异常时抛出
     */
    protected <T> T executeForJson(Request request, Class<T> type, String action) {
        try (Response response = okHttpClient.newCall(request).execute()) {
            String body = readBody(response);
            if (!response.isSuccessful()) {
                // 状态码是「该怎么应对」的第一个判据（400 该降级、429/5xx 该重试），
                // 因此给它一个可下钻的类型，而不是只留在消息文本里
                throw new LlmHttpException(action + " failed for provider: " + provider.getName()
                        + " (HTTP " + response.code() + "): " + truncate(body), response.code());
            }
            if (body.isEmpty()) {
                throw new JellyfishException(action + " returned an empty body for provider: " + provider.getName());
            }
            return ObjectMapperWrapper.readValue(body, type);
        } catch (JellyfishException e) {
            throw e;
        } catch (IOException e) {
            throw new JellyfishException(action + " failed for provider: " + provider.getName(), e);
        }
    }

    // ------------------------------------------------------------------
    // 流式
    // ------------------------------------------------------------------

    /**
     * 在后台线程启动一次流式请求，并把解析出的事件回调给监听器。
     *
     * @param request  流式请求
     * @param listener 流式响应监听器，不可为空
     * @param decoder  本次流响应的解码器
     * @return 可用于取消本次请求的句柄
     * @throws JellyfishException 监听器为空或线程池拒绝任务时抛出
     */
    protected LlmStreamHandle startStream(Request request, LlmStreamListener listener, StreamDecoder decoder) {
        if (listener == null) {
            throw new JellyfishException("stream listener must not be null");
        }
        // 流式响应可能长时间没有数据，这里禁用读超时；连接池/调度器与共享客户端一致
        OkHttpClient streamingClient = okHttpClient.newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        Call call = streamingClient.newCall(request);
        try {
            streamExecutor.execute(() -> runStream(call, listener, decoder));
        } catch (RejectedExecutionException e) {
            throw new JellyfishException(
                    "stream executor rejected the task for provider: " + provider.getName(), e);
        }
        return call::cancel;
    }

    /**
     * 执行流式请求并驱动监听器回调，运行在流式线程池中。
     * <p>
     * 终态回调 {@code onComplete} 刻意放在 try/catch 之外，保证监听器自身抛出的异常不会被转换成
     * {@code onError}，从而维持「{@code onError} 与 {@code onComplete} / {@code onCancelled} 互斥」的约定。
     *
     * @param call     待执行的流式请求
     * @param listener 流式响应监听器
     * @param decoder  本次流响应的解码器
     */
    private void runStream(Call call, LlmStreamListener listener, StreamDecoder decoder) {
        LlmResponse completed;
        try (Response httpResponse = call.execute()) {
            if (!httpResponse.isSuccessful()) {
                listener.onError(new LlmHttpException("stream request failed for provider: "
                        + provider.getName() + " (HTTP " + httpResponse.code() + "): "
                        + truncate(readBody(httpResponse)), httpResponse.code()));
                return;
            }
            listener.onOpen();
            readSse(httpResponse, listener, decoder);
            completed = decoder.buildResponse();
        } catch (IOException e) {
            if (call.isCanceled()) {
                listener.onCancelled();
            } else {
                listener.onError(new JellyfishException(
                        "stream request failed for provider: " + provider.getName(), e));
            }
            return;
        } catch (JellyfishException e) {
            listener.onError(e);
            return;
        } catch (Throwable t) {
            listener.onError(new JellyfishException(
                    "stream request failed for provider: " + provider.getName(), t));
            return;
        }
        // 终态回调放在 try 之外：监听器自身抛错不应再触发 onError，保证与 onError 互斥
        listener.onComplete(completed);
    }

    /**
     * 逐行读取 SSE 响应，把完整事件交给 {@link #dispatch} 处理。
     *
     * @param response HTTP 响应
     * @param listener 流式响应监听器
     * @param decoder  本次流响应的解码器
     * @throws IOException 读取响应流失败时抛出
     */
    private void readSse(Response response, LlmStreamListener listener, StreamDecoder decoder) throws IOException {
        if (response.body() == null) {
            throw new JellyfishException("stream response has no body for provider: " + provider.getName());
        }
        BufferedSource source = response.body().source();
        StringBuilder data = new StringBuilder();
        String event = null;
        String line;
        while ((line = source.readUtf8Line()) != null) {
            if (line.isEmpty()) {
                if (dispatch(event, data, listener, decoder)) {
                    return;
                }
                event = null;
                data.setLength(0);
                continue;
            }
            if (line.charAt(0) == ':') {
                continue;
            }
            if (line.startsWith("data:")) {
                String value = line.substring("data:".length());
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(value);
            } else if (line.startsWith("event:")) {
                event = line.substring("event:".length()).trim();
            }
        }
        dispatch(event, data, listener, decoder);
    }

    /**
     * 派发一个已解析完成的 SSE 事件。
     *
     * @param event    事件名，可为 {@code null}
     * @param data     事件数据缓冲，方法内不会清空
     * @param listener 流式响应监听器
     * @param decoder  本次流响应的解码器
     * @return {@code true} 表示收到结束标记或解码器要求结束，应停止读取
     */
    private boolean dispatch(String event, StringBuilder data, LlmStreamListener listener, StreamDecoder decoder) {
        if (data.length() == 0) {
            return false;
        }
        String payload = data.toString();
        if ("[DONE]".equals(payload.trim())) {
            return true;
        }
        return decoder.onData(event, payload, listener);
    }

    /**
     * 读取完整响应体为字符串。
     *
     * @param response HTTP 响应
     * @return 响应体字符串，无响应体时返回空串
     * @throws IOException 读取失败时抛出
     */
    protected String readBody(Response response) throws IOException {
        if (response.body() == null) {
            return "";
        }
        return response.body().string();
    }

    /**
     * 截断过长的响应体，避免把整段错误响应写进异常信息。
     *
     * @param value 原始响应体
     * @return 截断后的响应体，{@code null} 归一化为空串
     */
    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= ERROR_BODY_LIMIT ? value : value.substring(0, ERROR_BODY_LIMIT) + "...";
    }

    /**
     * 合并请求级系统提示词与消息列表中携带的 system 消息。
     * <p>
     * Claude / Gemini 等原生协议要求系统提示词独立于 messages 下发，但统一模型允许 system 消息
     * 混在消息列表里，因此这里统一抽取并按出现顺序拼接。
     *
     * @param request 统一请求模型
     * @return 合并后的系统提示词，无内容时返回空字符串
     */
    protected static String collectSystemPrompt(LlmRequest request) {
        return collectSystemPrompt(request, Collections.<Integer>emptySet());
    }

    /**
     * 收集 system prompt，但<b>跳过指定下标的消息区 system 消息</b>。
     * <p>
     * <b>为什么要这个变体</b>：把消息区的 system 消息上提到顶层 system 字段，等于改了请求的
     * <b>第 0 个 token</b>——前缀缓存随之整段作废。而 Anthropic 支持会话中途的 system 消息，
     * 那是「不能伪造的 operator 通道」：它排在历史之后，因此注入它不会动到已经缓存的前缀。
     * 对那些能满足厂商放置规则的消息，应当留在原位而不是上提。
     * <p>
     * <b>但留在原位的前提是「顶层不能再出现同一段文本」</b>：否则同一段话会真的下发两次。
     * 因此上提与原地保留必须是同一个决定的两面，由调用方把「已决定保留的下标」传进来。
     *
     * @param request      统一请求模型
     * @param keptIndexes  已决定保留在原位的消息下标，不可为 {@code null}
     * @return 上提到顶层 system 的文本；无内容时为空串
     */
    protected static String collectSystemPrompt(LlmRequest request, Set<Integer> keptIndexes) {
        StringBuilder builder = new StringBuilder();
        if (LlmClients.isNotBlank(request.getSystemPrompt())) {
            builder.append(request.getSystemPrompt());
        }
        List<LlmMessage> messages = request.getMessages();
        for (int index = 0; index < messages.size(); index++) {
            LlmMessage message = messages.get(index);
            if (!LlmMessage.ROLE_SYSTEM.equals(message.getRole())
                    || keptIndexes.contains(Integer.valueOf(index))
                    || !LlmClients.isNotBlank(message.getContent())) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(message.getContent());
        }
        return builder.toString();
    }

    /**
     * 从 usage（或它的某个子节点）读一个计数；字段缺失、非数字或为 {@code null} 时取缺省值。
     * <p>
     * <b>为什么用 {@code isNumber} 而不是靠 {@code asInt} 兜底</b>：{@code asInt} 对非数字节点也返回 0，
     * 于是「厂商把字段写成了字符串」与「字段真的缺失」会得到同一个结果——而那正是判断
     * 「厂商到底有没有返回用量」时最容易出错的地方。这里把两者分开：非数字视为没有这份信息。
     *
     * @param node     承载计数的节点，可为 {@code null}
     * @param field    字段名，不可为 {@code null}
     * @param fallback 缺省值
     * @return 计数；字段缺失或非数字时返回 {@code fallback}
     */
    protected static int intField(JsonNode node, String field, int fallback) {
        if (node == null) {
            return fallback;
        }
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asInt() : fallback;
    }

    /**
     * 判断 usage 节点是否代表「厂商根本没有返回用量」。
     *
     * @param usage usage JSON 节点，可为 {@code null}
     * @return 节点缺失或为空返回 {@code true}
     */
    protected static boolean isMissingUsage(JsonNode usage) {
        return usage == null || usage.isMissingNode() || usage.isNull();
    }

    /**
     * 构造一次调用的用量；全部计数都为 0 时视为厂商未返回用量，返回 {@code null}。
     * <p>
     * <b>调用方传入的 {@code prompt} 必须是「总输入」</b>（含缓存命中与建缓存的部分）。各厂商的口径并
     * 不一致——Anthropic 的 {@code input_tokens} 与两个缓存字段是互斥划分，而 OpenAI / DeepSeek /
     * Gemini 的输入字段本就已经包含缓存部分——归一化在各自的 {@code parseUsage} 里完成，
     * 本方法只负责判空与兜底。口径的完整说明见 {@link LlmUsage}。
     *
     * @param prompt     输入 token 总数
     * @param completion 输出 token 数
     * @param total      总 token 数，厂商不提供时传 0（由 {@link LlmUsage} 相加兜底）
     * @param cacheRead  命中缓存的输入 token 数，厂商不提供时传 0
     * @param cacheWrite 写入缓存的输入 token 数，厂商不提供时传 0
     * @return token 使用量，厂商未返回时为 {@code null}
     */
    protected static LlmUsage usageOf(int prompt, int completion, int total, int cacheRead, int cacheWrite) {
        if (prompt == 0 && completion == 0 && total == 0 && cacheRead == 0 && cacheWrite == 0) {
            return null;
        }
        return new LlmUsage(prompt, completion, total, cacheRead, cacheWrite);
    }

    /**
     * 单个流式响应的解码器。实现方自行维护累计状态，生命周期与单次流式请求一致。
     *
     * @author zcd
     */
    protected interface StreamDecoder {

        /**
         * 处理一个 SSE 事件。
         *
         * @param event    事件名，可为 {@code null}
         * @param data     事件数据
         * @param listener 流式响应监听器
         * @return {@code true} 表示流已结束，可以停止读取
         */
        boolean onData(String event, String data, LlmStreamListener listener);

        /**
         * 流正常结束时构造最终聚合结果。
         *
         * @return 本次流式调用的聚合结果
         */
        LlmResponse buildResponse();
    }
}
