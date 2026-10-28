package io.github.zcd0831.jellyfish.application.action;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 端口路由表：**共享可变状态**，插件启停时原子替换（08-application §8.11）。
 * 为什么不是 final 引用：插件实现必须实时生效。
 */
public final class PortRoutes {

    private final Map<String, Object> delegates = new ConcurrentHashMap<String, Object>();

    /** 当前启用者；没有则用默认实现。 */
    @SuppressWarnings("unchecked")
    public <T> T resolve(String capability, T fallback) {
        Object delegate = delegates.get(capability);
        return delegate == null ? fallback : (T) delegate;
    }

    public void replaceAll(Map<String, Object> next) {
        Map<String, Object> copy = new LinkedHashMap<String, Object>(next);
        delegates.keySet().retainAll(copy.keySet());
        delegates.putAll(copy);
    }

    public Map<String, Object> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, Object>(delegates));
    }
}
