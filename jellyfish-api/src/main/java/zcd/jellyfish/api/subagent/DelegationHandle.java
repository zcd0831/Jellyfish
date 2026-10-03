package zcd.jellyfish.api.subagent;

import java.util.Objects;

/**
 * 一次已派生的委派的句柄：等待结果或取消它。
 * <p>
 * <b>{@link #await()} 阻塞在调用方自己的线程上</b>，这是刻意的：编排工具已经占着一条工具执行线程，
 * 它本来就要等结果才能回灌给模型；反过来，让内核在完成时回调插件会把执行资源的生死交给插件
 * （内核不在关键路径上同步回调插件）。因此等待是「编排方等内核」，不是「内核等插件」。
 * <p>
 * <b>扇出靠「先全部派生、再逐个等待」</b>：{@link SubAgentPort#spawn(DelegationRequest)} 立即返回，
 * 所以连发 N 个之后 N 个 run 已经在并发执行，等待只是收结果。
 * <p>
 * <b>{@link #await()} 幂等</b>：同一个句柄重复等待返回同一个结果，不会重复触发收尾。
 *
 * @author zcd
 */
public interface DelegationHandle {

    /**
     * 获取 run 标识。
     *
     * @return run 标识；委派没开始时为 {@code null}
     */
    String runId();

    /**
     * 等待终态并返回结果；同一句柄重复调用返回同一个结果。
     *
     * @return 结果，保证非 {@code null}
     */
    DelegationResult await();

    /**
     * 取消这次委派（幂等）。
     * <p>
     * 已经带终态结果的句柄上无可取消：那是「还没开始就已经结束」。
     */
    void cancel();

    /**
     * 构造一个「已有结果」的句柄：不派生任何东西，等待立即返回给定结果。
     * <p>
     * 供能力缺失（{@link SubAgentPort#unavailable()}）与准入被拒两条路径使用，
     * 让调用方不必区分「真的跑过」与「根本没跑」——它照旧 {@code spawn → await}。
     *
     * @param result 结果，不可为 {@code null}
     * @return 句柄，保证非 {@code null}
     */
    static DelegationHandle settled(final DelegationResult result) {
        Objects.requireNonNull(result, "result must not be null");
        return new DelegationHandle() {

            @Override
            public String runId() {
                return result.getRunId();
            }

            @Override
            public DelegationResult await() {
                return result;
            }

            @Override
            public void cancel() {
                // 没有可取消的东西：结果在创建时就已确定
            }
        };
    }
}
