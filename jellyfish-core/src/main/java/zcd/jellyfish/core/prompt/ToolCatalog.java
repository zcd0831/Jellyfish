package zcd.jellyfish.core.prompt;

import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolDescriptor;
import zcd.jellyfish.infra.extension.DescriptorBinding;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.llm.LlmTool;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 工具目录：把注册表里的 {@link ToolDescriptor} 投影成厂商无关的 {@link LlmTool}。
 * <p>
 * <b>不维护第二份目录</b>：工具描述符随 handler 一起落 {@code TypeRegistry}，插件下架时描述符
 * 自动消失，这里现取即可。
 * <p>
 * <b>但按会话冻结一份清单</b>（{@link #tools(String, ToolFilter)}）。这是本类最容易被误解的一条，
 * 因此把理由写全：工具清单进的是缓存前缀里一个很靠前的位置（多数厂商的模板把它排在 messages
 * <b>之前</b>），因此<b>它变一个字，整段请求连同全部历史都作废</b>。而注册表是活的——{@code MCP}
 * 插件会在启动等待窗口超时后才连上 server、也会在 server 报 {@code tools/list_changed} 时整批重注册。
 * 会话跑到一半突然多出或少了几个工具，代价是那一轮之后的前缀全部重建。
 * <p>
 * 会话首次取清单时拍一份快照，之后这个会话一直用它；变化只对<b>新会话</b>生效。单会话内
 * 「工具清单逐字节不变」于是从「祈祷没人改」变成一条结构性的事实。
 * <p>
 * <b>代价与它的封闭性</b>：会话期间不会再跟随注册表变化。这一点在本项目里几乎无成本——
 * 内核<b>没有运行期插件重载</b>（{@code unload} 只发生在关机与启动回滚时，都在会话之前），
 * 唯一的变动源就是上面那个 MCP。若某天真的要做热部署，它会与这条保证直接冲突，
 * 届时应把它做成显式的「重建会话工具清单」动作，而不是默默放宽这里。
 * <p>
 * <b>顺带封住的一个隐患</b>：压缩的 cache-safe fork 会再取一次工具清单。若两次之间恰好变了，
 * fork 的前缀会无声地对不上，整段缓存白废——而那种不一致从日志里看不出来。冻结之后不会发生。
 * <p>
 * <b>不缓存但顺序稳定</b>：排序键是 {@code order} 升序 + <b>名称</b>，而不是注册表给的
 * 「{@code order} + 注册顺序」——注册顺序就是插件加载顺序，同序的两项谁先谁后会随加载时机变化。
 *
 * @author zcd
 */
@Singleton
public class ToolCatalog {

    /** 最多同时冻结多少个会话的工具清单。 */
    private static final int MAX_SESSIONS = 64;

    /**
     * 工具清单的排序键：{@code order} 升序，同序按<b>名称</b>。
     * <p>
     * 名称用作同序的第二关键字，而不是回到注册顺序：名称是内容的一部分，注册顺序不是。
     */
    private static final Comparator<DescriptorBinding<ToolDescriptor>> TOOL_ORDER =
            Comparator.comparingInt(DescriptorBinding<ToolDescriptor>::getOrder)
                    .thenComparing(binding -> nameOf(binding.getDescriptor()));

    /** 同步扩展点策略，工具描述符的唯一来源。 */
    private final ExtensionRegistry extensions;

    /** 会话标识 → 冻结的工具清单（未过滤），按访问顺序淘汰。 */
    private final Map<String, List<LlmTool>> snapshots = new LruSnapshots();

    /**
     * 构造工具目录。
     *
     * @param extensions 同步扩展点策略，不可为 {@code null}
     */
    @Inject
    public ToolCatalog(ExtensionRegistry extensions) {
        this.extensions = Objects.requireNonNull(extensions, "extensions must not be null");
    }

    /**
     * 实时列出当前注册的全部工具。
     * <p>
     * <b>不要用它拼请求</b>：它读到的是「此刻注册表里有什么」，会话中途的注册变化会当场反映出来，
     * 而那正是整段前缀作废的原因。请求路径必须走 {@link #tools(String, ToolFilter)}。
     *
     * @return 不可修改的 {@link LlmTool} 列表，无工具时为空列表
     */
    public List<LlmTool> tools() {
        return tools(ToolFilter.none());
    }

    /**
     * 实时列出经过滤的工具定义。
     * <p>
     * 与 {@link #tools(String, ToolFilter)} 的差别只有一条：<b>不冻结</b>。给「只想看看现在有什么」
     * 的诊断类调用点用（也正因为不冻结，测试可以直接改注册表观察结果）。
     * <p>
     * <b>过滤只作用于清单</b>：被滤掉的工具仍然可以在执行期被调用（只是模型看不到它，
     * 不会主动去调）；执行期的准入仍然由权限判定把关。这条分工是刻意的——
     * 清单是「建议」，权限是「约束」。
     *
     * @param filter 过滤器，不可为 {@code null}
     * @return 不可修改的 {@link LlmTool} 列表，无命中时为空列表
     */
    public List<LlmTool> tools(ToolFilter filter) {
        Objects.requireNonNull(filter, "filter must not be null");
        return accept(currentOf(), filter);
    }

    /**
     * 列出某个会话可用的工具定义：<b>清单在该会话首次用到时冻结</b>，此后不再变化。
     * <p>
     * 这是请求路径唯一该用的入口，理由见类注释。
     *
     * @param sessionId 会话标识；{@code null} 表示无从归属，退化成实时取一份（不冻结）
     * @param filter    过滤器，不可为 {@code null}
     * @return 不可修改的 {@link LlmTool} 列表，无命中时为空列表
     */
    public List<LlmTool> tools(String sessionId, ToolFilter filter) {
        Objects.requireNonNull(filter, "filter must not be null");
        return accept(snapshotOf(sessionId), filter);
    }

    /**
     * 取某个会话的冻结清单，没有就现拍一份。
     * <p>
     * 冻结的是<b>未过滤</b>的整份清单，过滤在之后做：同一个会话里过滤器可能不同
     * （子代理回合走 {@link ToolFilter}），而它们必须基于同一份底稿，否则「集合是否变过」
     * 这件事又说不清了。
     *
     * @param sessionId 会话标识，可为 {@code null}
     * @return 冻结的清单，不可修改
     */
    private synchronized List<LlmTool> snapshotOf(String sessionId) {
        if (sessionId == null) {
            return currentOf();
        }
        List<LlmTool> snapshot = snapshots.get(sessionId);
        if (snapshot == null) {
            snapshot = currentOf();
            snapshots.put(sessionId, snapshot);
        }
        return snapshot;
    }

    /**
     * 实时投影出当前注册表的整份工具清单，已按 {@link #TOOL_ORDER} 排序。
     *
     * @return 不可修改的清单
     */
    private List<LlmTool> currentOf() {
        List<DescriptorBinding<ToolDescriptor>> bindings =
                new ArrayList<DescriptorBinding<ToolDescriptor>>(
                        extensions.descriptorBindings(ToolCallRequest.class, ToolDescriptor.class));
        bindings.sort(TOOL_ORDER);
        List<LlmTool> tools = new ArrayList<LlmTool>(bindings.size());
        for (DescriptorBinding<ToolDescriptor> binding : bindings) {
            ToolDescriptor descriptor = binding.getDescriptor();
            if (descriptor == null) {
                continue;
            }
            tools.add(new LlmTool(descriptor.getName(), descriptor.getDescription(),
                    descriptor.getParameters(), descriptor.getRequired()));
        }
        return Collections.unmodifiableList(tools);
    }

    /**
     * 按过滤器挑出可下发的工具。
     *
     * @param tools  候选清单，不可为 {@code null}
     * @param filter 过滤器，不可为 {@code null}
     * @return 不可修改的清单
     */
    private static List<LlmTool> accept(List<LlmTool> tools, ToolFilter filter) {
        List<LlmTool> accepted = new ArrayList<LlmTool>(tools.size());
        for (LlmTool tool : tools) {
            if (filter.accepts(tool.getName())) {
                accepted.add(tool);
            }
        }
        return Collections.unmodifiableList(accepted);
    }

    /**
     * 取描述符里的工具名，供排序用。
     *
     * @param descriptor 描述符，可为 {@code null}
     * @return 工具名；描述符缺失时返回空串，使这类项排到同序的最前
     */
    private static String nameOf(ToolDescriptor descriptor) {
        return descriptor == null || descriptor.getName() == null ? "" : descriptor.getName();
    }

    /**
     * 按访问顺序淘汰的会话快照表。
     */
    private static final class LruSnapshots extends LinkedHashMap<String, List<LlmTool>> {

        /** 序列化标识（本类从不序列化，仅为满足 Serializable 约定）。 */
        private static final long serialVersionUID = 1L;

        /**
         * 构造按访问顺序排序的映射。
         */
        LruSnapshots() {
            super(16, 0.75f, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<LlmTool>> eldest) {
            return size() > MAX_SESSIONS;
        }
    }
}
