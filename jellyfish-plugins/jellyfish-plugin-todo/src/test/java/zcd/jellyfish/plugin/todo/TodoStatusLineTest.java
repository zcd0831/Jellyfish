package zcd.jellyfish.plugin.todo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.api.extension.StatusLineContribution;
import zcd.jellyfish.api.extension.StatusLineContributionRequest;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TodoStatusLine} 的单元测试。
 *
 * @author zcd
 */
@DisplayName("待办状态栏贡献")
class TodoStatusLineTest {

    /** 每个用例一个独立目录。 */
    @TempDir
    Path directory;

    /** 被测仓库。 */
    private TodoStore store;

    /** 被测处理器。 */
    private TodoStatusLine statusLine;

    @BeforeEach
    void setUp() {
        store = new TodoStore(directory);
        statusLine = new TodoStatusLine(store);
    }

    @Test
    @DisplayName("显示完成数 / 总数")
    void handle_should_renderProgress() {
        store.replace("s-1", Arrays.asList(new TodoItem("写文档", true), new TodoItem("跑测试", false)));

        assertEquals("待办 1/2", statusLine.handle(new StatusLineContributionRequest("s-1")).getText());
    }

    @Test
    @DisplayName("没有待办时不贡献：空的 0/0 只是噪音，还占掉别人的列")
    void handle_should_returnEmpty_when_noTodos() {
        StatusLineContribution contribution = statusLine.handle(new StatusLineContributionRequest("s-1"));

        assertTrue(contribution.isEmpty());
    }

    @Test
    @DisplayName("没有会话上下文时不贡献，不抛异常打扰界面")
    void handle_should_returnEmpty_when_noSession() {
        assertTrue(statusLine.handle(new StatusLineContributionRequest(null)).isEmpty());
    }

    @Test
    @DisplayName("只读当前会话那一份，别的会话的待办不影响本会话的进度")
    void handle_should_ignoreOtherSessions() {
        store.replace("s-2", Collections.singletonList(new TodoItem("别人的事", false)));

        assertTrue(statusLine.handle(new StatusLineContributionRequest("s-1")).isEmpty());
    }
}
