package zcd.jellyfish.plugin.todo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.extension.PromptContribution;
import zcd.jellyfish.api.extension.PromptContributionRequest;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TodoPromptContribution} 的单元测试。
 *
 * @author zcd
 */
@DisplayName("待办提示词贡献")
class TodoPromptContributionTest {

    /** 每个用例一个独立目录。 */
    @TempDir
    Path directory;

    /** 被测仓库。 */
    private TodoStore store;

    /** 被测贡献处理器。 */
    private TodoPromptContribution contribution;

    @BeforeEach
    void setUp() {
        store = new TodoStore(directory);
        contribution = new TodoPromptContribution(store);
    }

    @Test
    @DisplayName("有待办时注入与内核原先一致的待办块")
    void handle_should_renderBlock() {
        store.replace("s-1", Arrays.asList(new TodoItem("写文档", false), new TodoItem("跑测试", true)));

        PromptContribution result = contribution.handle(new PromptContributionRequest("s-1"));

        assertTrue(!result.isEmpty());
        assertEquals("[待办]\n- [ ] 写文档\n- [x] 跑测试", result.getText());
    }

    @Test
    @DisplayName("没有待办时返回空贡献：本处理器每轮都会被问到")
    void handle_should_returnEmpty_when_noTodos() {
        PromptContribution result = contribution.handle(new PromptContributionRequest("s-1"));

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("没有会话上下文时返回空贡献，不抛异常打扰对话")
    void handle_should_returnEmpty_when_noSession() {
        assertTrue(contribution.handle(new PromptContributionRequest(null)).isEmpty());
    }
}
