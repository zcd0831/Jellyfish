package zcd.jellyfish.plugin.todo;

import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PanelContribution;
import zcd.jellyfish.api.extension.PanelContributionRequest;
import zcd.jellyfish.api.ui.UiEmphasis;
import zcd.jellyfish.api.ui.UiLine;
import zcd.jellyfish.api.ui.UiRegion;
import zcd.jellyfish.api.ui.UiSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * 面板贡献处理器：把当前会话的待办清单放成一块常驻面板。
 * <p>
 * <b>为什么有了状态栏还要面板</b>：状态栏只有一行，能表达「还剩几件事」却表达不了「哪几件」；
 * 而「模型正在做什么、做到哪儿了」恰恰需要看见清单本身。两者是不同粒度，不是重复。
 * <p>
 * <b>建议落右栏（{@code RIGHT}）而不是停靠区</b>：待办是纵向长的清单，横着放会把消息区顶掉好几行；
 * 侧栏天然适合列表。但这是<b>软建议</b>——外壳可能忽略它（终端太窄时侧栏整体隐藏），
 * 也可能被用户用 {@code /ui} 改掉，插件不得假设自己一定在右边。
 * <p>
 * <b>没有待办时返回空贡献</b>：面板要独占一整个区域，没内容就不该抢地盘——
 * 这一点比状态栏更严格（状态栏只是不占列，面板是占一块地方）。
 * <p>
 * <b>与「处理器不得做 I/O」契约的关系</b>：与 {@link TodoStatusLine} 同一处有意识的偏离
 * （{@link TodoStore} 每会话首次访问时懒加载一次，之后命中内存缓存）。
 *
 * @author zcd
 */
final class TodoPanel implements ExtensionHandler<PanelContributionRequest, PanelContribution> {

    /** 面板标题。 */
    private static final String TITLE = "待办";

    /** 待办仓库。 */
    private final TodoStore store;

    /**
     * 构造处理器。
     *
     * @param store 待办仓库，不可为 {@code null}
     */
    TodoPanel(TodoStore store) {
        this.store = store;
    }

    @Override
    public PanelContribution handle(PanelContributionRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null) {
            return PanelContribution.empty();
        }
        List<TodoItem> items = store.itemsOf(sessionId);
        if (items.isEmpty()) {
            return PanelContribution.empty();
        }
        List<UiLine> lines = new ArrayList<UiLine>(items.size());
        for (TodoItem item : items) {
            lines.add(lineOf(item));
        }
        return PanelContribution.of(TITLE, lines, UiRegion.RIGHT);
    }

    /**
     * 渲染一条待办。
     * <p>
     * 已完成整行用 {@link UiEmphasis#DIM}：面板的用处是「看还剩什么」，做完的事应当退到背景里，
     * 而不是和未完成项一样抢眼。
     *
     * @param item 待办项
     * @return 界面行
     */
    private static UiLine lineOf(TodoItem item) {
        UiEmphasis emphasis = item.done() ? UiEmphasis.DIM : UiEmphasis.NORMAL;
        return UiLine.of(UiSegment.of(TodoText.mark(item), emphasis),
                UiSegment.of(item.content(), emphasis));
    }
}
