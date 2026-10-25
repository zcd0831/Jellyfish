package io.github.zcd0831.jellyfish.application.action;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置工具处理器注册表（按工具 id 索引）。规则出处：模块-行动 §8.1 的"处理器注册表"。
 */
public final class InternalToolHandlers {

    /** 插件启停会增删处理器 ⇒ 必须并发安全且可写（M42）。 */
    private final Map<String, InternalToolHandler> byName =
            new java.util.concurrent.ConcurrentHashMap<String, InternalToolHandler>();

    public InternalToolHandlers(List<NamedHandler> handlers) {
        for (NamedHandler handler : handlers) {
            byName.put(handler.name, handler.handler);
        }
    }

    public static InternalToolHandlers empty() {
        return new InternalToolHandlers(java.util.Collections.<NamedHandler>emptyList());
    }

    /** 插件装/卸时增删：`PLUGIN` 来源的工具与内置工具共用这张表（模块-行动 §8.1）。 */
    public void register(String toolName, InternalToolHandler handler) {
        byName.put(toolName, handler);
    }

    public void unregister(String toolName) {
        byName.remove(toolName);
    }

    public InternalToolHandler find(String toolName) {
        return byName.get(toolName);
    }

    /** handler 与其工具名的绑定：工具名来自 `ToolSource.endpointOrIdentifier`。 */
    public static final class NamedHandler {

        private final String name;
        private final InternalToolHandler handler;

        public NamedHandler(String name, InternalToolHandler handler) {
            this.name = name;
            this.handler = handler;
        }
    }
}
