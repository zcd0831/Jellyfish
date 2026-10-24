package io.github.zcd0831.jellyfish.infrastructure.contract;

import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.domain.action.tool.SideEffectLevel;
import io.github.zcd0831.jellyfish.domain.action.tool.SourceType;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolDefinition;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolSchema;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolSource;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolStatus;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** `CatalogRepository` 的契约：五类目录可读、保存后可再读到、内置资产始终存在。 */
public abstract class CatalogRepositoryContract {

    protected abstract CatalogRepository repository();

    protected static ToolDefinition tool(String id) {
        return new ToolDefinition(ToolId.of(id), id, "说明", new ToolSchema("{\"type\":\"object\"}"), null,
                new ToolSource(SourceType.REST, "http://localhost/" + id), SideEffectLevel.READ_ONLY, 1000,
                ToolStatus.ACTIVE);
    }

    @Test
    void 保存工具后能读回() {
        repository().saveTool(tool("my_tool"));

        boolean found = false;
        for (ToolDefinition candidate : repository().tools()) {
            if (candidate.id().getValue().equals("my_tool")) {
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test
    void 内置资产不依赖磁盘即可读到() {
        assertTrue(repository().agents().stream().anyMatch(a -> a.id().getValue().startsWith("builtin.")));
        assertTrue(repository().tools().stream().anyMatch(t -> t.name().equals("ask_user")));
    }

    @Test
    void 空目录返回空列表而不是null() {
        assertEquals(0, repository().providers().size());
        assertEquals(0, repository().models().size());
        assertEquals(0, repository().skills().size());
    }
}
