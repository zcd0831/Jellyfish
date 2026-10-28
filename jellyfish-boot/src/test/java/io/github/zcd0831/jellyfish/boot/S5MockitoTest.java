package io.github.zcd0831.jellyfish.boot;

import io.github.zcd0831.jellyfish.application.action.ManageCatalogUseCase;
import io.github.zcd0831.jellyfish.application.action.port.CatalogRepository;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.domain.action.tool.SideEffectLevel;
import io.github.zcd0831.jellyfish.domain.action.tool.SourceType;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolDefinition;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolSchema;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolSource;
import io.github.zcd0831.jellyfish.domain.action.tool.ToolStatus;
import io.github.zcd0831.jellyfish.domain.shared.ToolId;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/**
 * 需要 mock 的场合才用 Mockito：这里只关心"保存有没有被调用"，
 * 不关心仓储怎么落盘。。
 */
class S5MockitoTest {

    @Test
    void 保存工具时调用仓储一次() {
        CatalogRepository catalog = Mockito.mock(CatalogRepository.class);
        ManageCatalogUseCase useCase = new ManageCatalogUseCase(catalog);
        ToolDefinition tool = new ToolDefinition(ToolId.of("my_tool"), "my_tool", "自定义工具",
                new ToolSchema("{\"type\":\"object\"}"), null,
                new ToolSource(SourceType.REST, "http://localhost/tool"), SideEffectLevel.READ_ONLY,
                1000, ToolStatus.ACTIVE);

        Result<Void> result = useCase.saveTool(tool);

        assertTrue(result.isOk());
        verify(catalog).saveTool(tool);
        verify(catalog, Mockito.never()).saveAgent(any());
    }
}
