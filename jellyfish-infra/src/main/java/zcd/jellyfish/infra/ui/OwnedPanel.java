package zcd.jellyfish.infra.ui;

import zcd.jellyfish.api.extension.PanelContribution;

import java.util.Objects;

/**
 * 带归属的面板贡献：某插件给的那一块面板。
 * <p>
 * <b>为什么必须带 owner</b>：面板是「独占型」资源（一块区域同时只显示一个），因此外壳需要
 * ①在清单里告诉用户「这块面板属于谁」、②让用户按 {@code pluginId} 指定显示、③按 {@code order}
 * 竞争时能说出被挤下去的是谁。owner 就是 pluginId，这一点由 {@code HandlerBinding} 天然给出。
 * <p>
 * 状态栏片段不需要这一层：它只是文本，拼接即共存，没有归属歧义（去重在收取时就做完了）。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class OwnedPanel {

    /** 贡献方标识（pluginId）。 */
    private final String owner;

    /** 面板内容。 */
    private final PanelContribution contribution;

    /**
     * 构造带归属的面板。
     *
     * @param owner        贡献方标识（pluginId），不可为 {@code null}
     * @param contribution 面板内容，不可为 {@code null}
     */
    public OwnedPanel(String owner, PanelContribution contribution) {
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
        this.contribution = Objects.requireNonNull(contribution, "contribution must not be null");
    }

    /**
     * 获取贡献方标识。
     *
     * @return pluginId，保证非 {@code null}
     */
    public String getOwner() {
        return owner;
    }

    /**
     * 获取面板内容。
     *
     * @return 面板内容，保证非 {@code null}
     */
    public PanelContribution getContribution() {
        return contribution;
    }

    @Override
    public String toString() {
        return "OwnedPanel{owner=" + owner + ", contribution=" + contribution + '}';
    }
}
