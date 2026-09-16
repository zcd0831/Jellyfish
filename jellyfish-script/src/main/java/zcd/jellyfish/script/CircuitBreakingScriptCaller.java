package zcd.jellyfish.script;

import com.fasterxml.jackson.databind.JsonNode;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.script.protocol.ScriptCallException;
import zcd.jellyfish.script.protocol.ScriptConnectionException;
import zcd.jellyfish.script.protocol.ScriptProtocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 带熔断的调用入口：按脚本记账，打开期间<b>立即拒绝、不派发</b>。
 * <p>
 * <b>它是一个装饰器，而不是网关的一部分</b>：熔断的判据是「这个脚本最近怎么样」，
 * 与「进程、协议、worker」无关。放在外面，状态机就能脱离进程被完整验证；
 * 放在网关里，验一次「冷却 60 秒后自动半开」就必须先起一个真实进程。
 * <p>
 * <b>为什么拒绝而不摘注册</b>：注册只能在插件 {@code start()} 窗口内发生，
 * 摘掉就意味着恢复必须走 {@code /reload}。熔断保留注册，工具仍在清单里，
 * 模型可能再调一次，但拿到的是一个带剩余时间的明确错误，看到就会避开；
 * 而冷却一过它自己会好——不需要任何人做任何事。
 * <p>
 * <b>哪些失败计入，是这里唯一需要判断的事</b>：
 * <ul>
 *   <li><b>不计</b> {@link ScriptConnectionException}：它是 L3（整门语言不可用），
 *       而不是这个脚本的问题。网关是懒启动的，下一次调用会重新拉起来；
 *       若把一次短暂的网关故障算进每个脚本的账上，结果是「网关好了，所有脚本还要再等一个冷却」。</li>
 *   <li><b>不计</b> {@code -32001}：那是熔断自己产生的拒绝。把它再计一次失败，
 *       冷却时间就会被自己的拒绝无限延长，永远不会自动恢复。</li>
 *   <li><b>其余都计</b>：超时、脚本回报的错误、协议错误码。判据是「这次调用没有拿到结果」——
 *       而失败的归因、要不要杀掉 worker，都不在这里决定。</li>
 * </ul>
 * 这条判断被收在一个方法里（{@link #countsAsFailure}），因为它是唯一一处
 * 「什么算坏消息」的定义；散落在调用点会让它随调用点漂移。
 * <p>
 * 线程安全：熔断器按脚本懒建、存放在并发映射里，调用可来自任意线程。
 *
 * @author zcd
 */
public final class CircuitBreakingScriptCaller implements ScriptCaller {

    /** 被装饰的调用入口。 */
    private final ScriptCaller delegate;

    /** 熔断参数。 */
    private final CircuitBreakerSettings settings;

    /** 状态变化观察者。 */
    private final ScriptCircuitListener listener;

    /** 按脚本标识持有的熔断器。 */
    private final Map<String, ScriptCircuitBreaker> breakers =
            new ConcurrentHashMap<String, ScriptCircuitBreaker>();

    /**
     * 构造调用入口。
     *
     * @param delegate 被装饰的调用入口，不可为 {@code null}
     * @param settings 熔断参数，不可为 {@code null}
     * @param listener 状态变化观察者，可为 {@code null}
     * @throws JellyfishException 参数为 {@code null} 时抛出
     */
    public CircuitBreakingScriptCaller(ScriptCaller delegate, CircuitBreakerSettings settings,
                                       ScriptCircuitListener listener) {
        if (delegate == null) {
            throw new JellyfishException("被装饰的脚本调用入口不可为 null");
        }
        if (settings == null) {
            throw new JellyfishException("熔断参数不可为 null");
        }
        this.delegate = delegate;
        this.settings = settings;
        this.listener = listener;
    }

    @Override
    public JsonNode call(ScriptPlugin plugin, String typeName, JsonNode request) {
        ScriptCircuitBreaker breaker = breakerOf(plugin.id());
        if (!breaker.admit()) {
            // 拒绝而不是派发：熔断的全部意义就在于「别再往一个已知有问题的脚本上打」
            throw new ScriptCallException(ScriptProtocol.CODE_CIRCUIT_OPEN, refusalMessage(breaker));
        }
        try {
            JsonNode result = delegate.call(plugin, typeName, request);
            breaker.recordSuccess();
            return result;
        } catch (JellyfishException e) {
            if (countsAsFailure(e)) {
                breaker.recordFailure();
            }
            throw e;
        }
    }

    /**
     * 获取某个脚本的熔断状态，供状态命令展示。
     *
     * @param scriptId 脚本标识
     * @return 状态；该脚本还没有任何调用时返回 {@link ScriptCircuitBreaker.State#CLOSED}
     */
    public ScriptCircuitBreaker.State stateOf(String scriptId) {
        ScriptCircuitBreaker breaker = breakers.get(scriptId);
        return breaker == null ? ScriptCircuitBreaker.State.CLOSED : breaker.state();
    }

    /**
     * 取出（必要时创建）某个脚本的熔断器。
     *
     * @param scriptId 脚本标识
     * @return 熔断器，保证非 {@code null}
     */
    private ScriptCircuitBreaker breakerOf(String scriptId) {
        ScriptCircuitBreaker found = breakers.get(scriptId);
        if (found != null) {
            return found;
        }
        // 并发下可能造出两个等价实例，但只有 putIfAbsent 成功的那个会被后续调用看到；
        // 被丢掉的那个没有共享状态，丢掉即可——为它加锁只会让每次首次调用都付一次全局锁
        ScriptCircuitBreaker created = new ScriptCircuitBreaker(scriptId, settings, listener);
        ScriptCircuitBreaker existing = breakers.putIfAbsent(scriptId, created);
        return existing != null ? existing : created;
    }

    /**
     * 判断一次失败是否计入熔断。
     *
     * @param failure 失败
     * @return 计入返回 {@code true}
     */
    private static boolean countsAsFailure(JellyfishException failure) {
        if (failure instanceof ScriptConnectionException) {
            return false;
        }
        if (failure instanceof ScriptCallException) {
            return !((ScriptCallException) failure).isCircuitOpen();
        }
        return true;
    }

    /**
     * 构造拒绝文案。
     * <p>
     * 文案要给出「还要等多久」：模型看到一次带时间的拒绝就会避开，而一句
     * 「脚本不可用」只会让它换个参数再试一次。
     *
     * @param breaker 熔断器
     * @return 文本，保证非 {@code null}
     */
    private static String refusalMessage(ScriptCircuitBreaker breaker) {
        if (breaker.state() == ScriptCircuitBreaker.State.PERMANENT) {
            return "脚本 " + breaker.scriptId() + " 已连续 " + breaker.openRounds()
                    + " 轮探测失败，已停止调用；修复后请执行 /reload";
        }
        long remainingSeconds = (breaker.retryAfterMillis() + 999L) / 1000L;
        return "脚本 " + breaker.scriptId() + " 已因连续 " + breaker.consecutiveFailures()
                + " 次失败熔断，将在 " + remainingSeconds + " 秒后自动重试";
    }

    /**
     * 构造 {@code 脚本 → 熔断状态} 映射，供台账渲染。
     *
     * @return 映射，保证非 {@code null}
     */
    public Map<String, String> states() {
        Map<String, String> states = new LinkedHashMap<String, String>();
        // 排序输出：并发映射的遍历顺序会变，而状态命令的输出不该每次都不一样
        List<String> ids = new ArrayList<String>(breakers.keySet());
        Collections.sort(ids);
        for (String id : ids) {
            states.put(id, breakers.get(id).describe());
        }
        return states;
    }
}
