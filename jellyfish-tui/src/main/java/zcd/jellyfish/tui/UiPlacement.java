package zcd.jellyfish.tui;

import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.ui.UiRegion;
import zcd.jellyfish.infra.ui.OwnedPanel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 面板落位状态：哪个插件的面板显示在哪个区域。
 * <p>
 * <b>为什么「落位」是外壳的状态而不是插件的数据</b>：插件看不到终端有多宽，也不知道别的插件占了什么，
 * 「我想放左栏」这件事在插件侧没有依据；而「一块区域同时只能显示一个」意味着这是竞争资源，
 * 必须由仲裁者处置——仲裁者只能是外壳与用户。因此插件给的是软建议（{@code preferredRegion}），
 * 这里做的是可以推翻它的决定。
 * <p>
 * <b>落位的默认规则</b>：用户指定过就以用户为准（不再跟随插件的建议）；否则用建议区域；
 * 没有建议、建议的不是面板区域、或建议的区域已经有别人（同一区域全部候选里 {@code order} 最小的先显示）
 * 时落到 {@link UiRegion#DOCK}。按 {@code order} 升序的候选由注册表给出，这里不再排序。
 * <p>
 * <b>关闭（{@code /ui <region> off}）只影响显示，不清候选</b>：所以「关掉再打开」不需要重新收集，
 * 也不会因为关闭而让插件的注册消失。
 * <p>
 * <b>刻意不持久化</b>：重启回到默认（用户没有表达过偏好时，插件声明的顺序就是最好的默认）。
 * <p>
 * 只在渲染线程读写。
 *
 * @author zcd
 */
final class UiPlacement {

    /** 用户手动指定的落位：pluginId → 区域。 */
    private final Map<String, UiRegion> assigned = new LinkedHashMap<String, UiRegion>();

    /** 被用户关闭显示的区域。 */
    private final Set<UiRegion> hidden = new LinkedHashSet<UiRegion>();

    /**
     * 判断一个区域是否为「面板区域」。
     * <p>
     * {@link UiRegion#STATUS} 不是：状态栏是拼接型单行，所有片段共存，没有「一块区域放一个」的语义。
     *
     * @param region 区域，可为 {@code null}
     * @return 是面板区域返回 {@code true}
     */
    static boolean isPanelRegion(UiRegion region) {
        return region != null && region != UiRegion.STATUS;
    }

    /**
     * 把收集到的面板按区域分组。
     * <p>
     * 返回的每个列表都是 {@code order} 升序（注册表已经排好），列表首元素就是该区域默认要显示的那个。
     * 用户指定过落位的插件会出现在指定区域，而不是它建议的区域。
     *
     * @param panels 收集到的面板，可为 {@code null}
     * @return 区域 → 候选面板（可能为空 map），保证非 {@code null}
     */
    Map<UiRegion, List<OwnedPanel>> candidates(List<OwnedPanel> panels) {
        Map<UiRegion, List<OwnedPanel>> byRegion = new EnumMap<UiRegion, List<OwnedPanel>>(UiRegion.class);
        if (panels == null) {
            return byRegion;
        }
        for (OwnedPanel panel : panels) {
            UiRegion region = regionOf(panel);
            List<OwnedPanel> list = byRegion.get(region);
            if (list == null) {
                list = new ArrayList<OwnedPanel>();
                byRegion.put(region, list);
            }
            list.add(panel);
        }
        return byRegion;
    }

    /**
     * 取出每个区域当前应当显示的面板。
     *
     * @param panels 收集到的面板，可为 {@code null}
     * @return 区域 → 面板，只含「有候选且未被关闭」的区域，保证非 {@code null}
     */
    Map<UiRegion, OwnedPanel> selected(List<OwnedPanel> panels) {
        Map<UiRegion, OwnedPanel> result = new EnumMap<UiRegion, OwnedPanel>(UiRegion.class);
        for (Map.Entry<UiRegion, List<OwnedPanel>> entry : candidates(panels).entrySet()) {
            UiRegion region = entry.getKey();
            if (!isPanelRegion(region) || hidden.contains(region) || entry.getValue().isEmpty()) {
                continue;
            }
            result.put(region, chooseFrom(region, entry.getValue()));
        }
        return result;
    }

    /**
     * 在一个区域的候选里选出要显示的那一个。
     * <p>
     * <b>用户指定优先于 {@code order}</b>：候选列表是 {@code order} 升序的，若直接取首元素，
     * {@code /ui dock <pluginId>} 就会静默失效——用户要的正是「不要按默认来」。
     *
     * @param region 区域
     * @param list   该区域的候选（{@code order} 升序）
     * @return 要显示的面板
     */
    private OwnedPanel chooseFrom(UiRegion region, List<OwnedPanel> list) {
        for (OwnedPanel panel : list) {
            if (region.equals(assigned.get(panel.getOwner()))) {
                return panel;
            }
        }
        return list.get(0);
    }

    /**
     * 判断一个区域是否被用户关闭。
     *
     * @param region 区域
     * @return 关闭返回 {@code true}
     */
    boolean isHidden(UiRegion region) {
        return hidden.contains(region);
    }

    /**
     * 把某个插件的面板钉到指定区域。
     * <p>
     * <b>会先清掉该区域已有的用户指定</b>：一个区域同时只能有一个「用户选中的那个」，
     * 否则 {@code /ui} 轮换累积出多个指定后，「最近一次指定」就不再生效——
     * 选谁显示会退化成「谁先被指定」。
     *
     * @param owner  插件标识
     * @param region 目标区域
     */
    void assign(String owner, UiRegion region) {
        if (owner == null || !isPanelRegion(region)) {
            throw new JellyfishException("只有面板区域可以指定落位");
        }
        assigned.entrySet().removeIf(entry -> region.equals(entry.getValue()));
        assigned.put(owner, region);
        hidden.remove(region);
    }

    /**
     * 关闭一个区域的显示。
     *
     * @param region 区域
     */
    void hide(UiRegion region) {
        hidden.add(region);
    }

    /**
     * 恢复一个区域的显示，并清掉该区域的用户指定落位。
     * <p>
     * 清掉指定是有意的：{@code /ui <region> off} 之后 {@code /ui <region>} 的意图是「回到默认」，
     * 而默认由插件的建议与 {@code order} 决定；若保留旧的指定，用户看到的是「关掉再打开还是那一个」。
     *
     * @param region 区域
     */
    void show(UiRegion region) {
        hidden.remove(region);
        assigned.values().removeAll(Collections.singleton(region));
    }

    /**
     * 在区域内轮换到下一个候选。
     * <p>
     * 轮换基于<b>当前实际显示者</b>而不是用户的指定：用户没指定过时显示的是 {@code order} 最小者，
     * 从它开始轮换才符合直觉（否则第一次按会「跳」到第二个）。
     *
     * @param region 区域
     * @param panels 收集到的面板，可为 {@code null}
     * @return 轮换后显示的面板；该区域没有候选时返回 {@code null}
     */
    OwnedPanel cycle(UiRegion region, List<OwnedPanel> panels) {
        if (!isPanelRegion(region)) {
            throw new JellyfishException("状态栏是拼接型区域，没有可轮换的面板");
        }
        List<OwnedPanel> list = candidates(panels).get(region);
        if (list == null || list.isEmpty()) {
            return null;
        }
        OwnedPanel current = selected(panels).get(region);
        int index = current == null ? -1 : indexOf(list, current.getOwner());
        OwnedPanel next = list.get((index + 1) % list.size());
        assign(next.getOwner(), region);
        return next;
    }

    /**
     * 在候选列表里找某个 owner 的位置。
     *
     * @param list  候选列表
     * @param owner 插件标识
     * @return 下标；找不到时返回 -1
     */
    private static int indexOf(List<OwnedPanel> list, String owner) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getOwner().equals(owner)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 决定一个面板落到哪个区域。
     * <p>
     * 用户指定优先于插件建议：用户按下 {@code /ui} 就是在表达「别再跟着插件走」。
     *
     * @param panel 面板
     * @return 区域，保证是面板区域
     */
    private UiRegion regionOf(OwnedPanel panel) {
        UiRegion forced = assigned.get(panel.getOwner());
        if (forced != null) {
            return forced;
        }
        UiRegion preferred = panel.getContribution().getPreferredRegion();
        return isPanelRegion(preferred) ? preferred : UiRegion.DOCK;
    }
}
