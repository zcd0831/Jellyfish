package io.github.zcd0831.jellyfish.application.action;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 端口路由表：**共享可变状态**，插件启停时原子替换（08-application §8.11）。
 * 为什么不是 final 引用：插件实现必须实时生效。
 * <p>
 * 替换必须是一次引用交换，不能"先删后加"：两步做法中间必然存在一张**半新半旧**的表，
 * 插件启停的瞬间正好 `resolve()` 的线程会读到它 —— 一半能力已经换成新插件、另一半还是旧的。
 * 表本身不可变（替换整张），所以读侧不加锁也能拿到完整一致的一张。
 */
public final class PortRoutes {

    private volatile Map<String, Object> delegates = Collections.emptyMap();

    /** 当前启用者；没有则用默认实现。 */
    @SuppressWarnings("unchecked")
    public <T> T resolve(String capability, T fallback) {
        Object delegate = delegates.get(capability);
        return delegate == null ? fallback : (T) delegate;
    }

    /** 整表替换：构造一张新的不可变表再一次性换掉引用，读侧只会看到旧表或新表。 */
    public void replaceAll(Map<String, Object> next) {
        delegates = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(next));
    }

    public Map<String, Object> snapshot() {
        return delegates;
    }
}
