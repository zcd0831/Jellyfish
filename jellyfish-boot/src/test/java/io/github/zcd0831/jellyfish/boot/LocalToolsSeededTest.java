package io.github.zcd0831.jellyfish.boot;

import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.domain.action.agent.AgentDefinition;
import io.github.zcd0831.jellyfish.domain.action.tool.SourceType;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolDefinition;
import io.github.zcd0831.jellyfish.domain.shared.AgentId;
import io.github.zcd0831.jellyfish.infrastructure.file.DataDirectory;
import io.github.zcd0831.jellyfish.infrastructure.file.FileCatalogRepository;
import io.github.zcd0831.jellyfish.infrastructure.file.JsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 13 份工具声明随 jar 走，主代理绑定全部 13 个。 */
class LocalToolsSeededTest {

    @TempDir
    Path tmp;

    @Test
    void 内置工具齐全且主代理可用() {
        CatalogRepository catalog = FileCatalogRepository.standalone(DataDirectory.of(tmp.toString()),
                new JsonCodec());

        List<String> local = new ArrayList<String>();
        for (ToolDefinition tool : catalog.tools()) {
            if (tool.source().getType() == SourceType.NATIVE) {
                local.add(tool.id().getValue());
            }
        }
        assertEquals(13, catalog.tools().size(), "5 个编排 + 8 个本地能力");
        assertEquals(8, local.size());
        assertTrue(local.contains("run_command"));
        assertTrue(boundTools(catalog).containsAll(local), "主代理必须能调用本地能力");
    }

    private static List<String> boundTools(CatalogRepository catalog) {
        List<String> ids = new ArrayList<String>();
        for (AgentDefinition agent : catalog.agents()) {
            if (agent.id().equals(AgentId.of("builtin.main"))) {
                agent.listBoundToolIds().forEach(id -> ids.add(id.getValue()));
            }
        }
        return ids;
    }
}
