package io.github.zcd0831.jellyfish.domain.action.plugin;

import io.github.zcd0831.jellyfish.domain.shared.DomainInvariantViolation;
import io.github.zcd0831.jellyfish.domain.shared.PluginId;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;

import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 一个插件的**授权意图**——唯一落盘的东西。
 * "已加载 / 加载失败"是进程内事实，不进这里（与 D22 同理：没人负责擦除的持久状态必然变脏）。
 * 规则出处：模块-行动 §3、§7.6，D24。
 */
public class PluginManifest {

    private final PluginId id;
    private final String version;
    private final int apiVersion;
    private final boolean trusted;
    private final Date grantedAt;
    private final Set<ToolId> toolIds;
    private final Set<String> declaredCapabilities;
    private final Set<String> grantedCapabilities;

    public PluginManifest(PluginId id, String version, int apiVersion, boolean trusted, Date grantedAt,
                          Set<ToolId> toolIds, Set<String> declaredCapabilities,
                          Set<String> grantedCapabilities) {
        this.id = id;
        this.version = version;
        this.apiVersion = apiVersion;
        this.trusted = trusted;
        this.grantedAt = grantedAt;
        this.toolIds = immutable(toolIds);
        this.declaredCapabilities = immutable(declaredCapabilities);
        this.grantedCapabilities = new LinkedHashSet<String>(
                grantedCapabilities == null ? Collections.<String>emptySet() : grantedCapabilities);
        requireSubset(this.grantedCapabilities, this.declaredCapabilities);
    }

    /** 安装：只记意图，此时插件还没有任何效果（授权在 enable/grant 时才发生）。 */
    public static PluginManifest create(PluginId id, String version, int apiVersion, Set<ToolId> toolIds,
                                        Set<String> declaredCapabilities, boolean trusted, Date grantedAt) {
        return new PluginManifest(id, version, apiVersion, trusted, grantedAt, toolIds,
                declaredCapabilities, Collections.<String>emptySet());
    }

    /** 按能力逐项勾选（§7.6 规则 3）：勾选不能超出插件自报的可替换面。 */
    public void grant(String capability) {
        if (!declaredCapabilities.contains(capability)) {
            throw new DomainInvariantViolation("插件 " + id.getValue() + " 未声明能力：" + capability);
        }
        grantedCapabilities.add(capability);
    }

    public void revoke(String capability) {
        grantedCapabilities.remove(capability);
    }

    /** 安装时一次同意；不再有逐次审批（§7.1 规则 5、D24-8）。 */
    public boolean isTrusted() {
        return trusted;
    }

    public boolean isGranted(String capability) {
        return grantedCapabilities.contains(capability);
    }

    private static void requireSubset(Set<String> granted, Set<String> declared) {
        for (String capability : granted) {
            if (!declared.contains(capability)) {
                throw new DomainInvariantViolation("已授权能力不在声明内：" + capability);
            }
        }
    }

    private static <T> Set<T> immutable(Set<T> source) {
        return Collections.unmodifiableSet(new LinkedHashSet<T>(
                source == null ? Collections.<T>emptySet() : source));
    }

    public PluginId id() {
        return id;
    }

    public String version() {
        return version;
    }

    /** 插件声明的宿主 API 版本；与宿主不符即不能安装或启用（§7.6 规则 5）。 */
    public int apiVersion() {
        return apiVersion;
    }

    public Date grantedAt() {
        return grantedAt;
    }

    public Set<ToolId> toolIds() {
        return toolIds;
    }

    public Set<String> declaredCapabilities() {
        return declaredCapabilities;
    }

    /** 授权是唯一可变的部分；对外只给不可变视图，改动走 grant / revoke。 */
    public Set<String> grantedCapabilities() {
        return Collections.unmodifiableSet(grantedCapabilities);
    }
}
