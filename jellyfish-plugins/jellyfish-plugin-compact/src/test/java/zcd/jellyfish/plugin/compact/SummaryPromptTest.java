package zcd.jellyfish.plugin.compact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CompactionStrategy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SummaryPrompt} 的打包体检：用真实资源，盯住「资源在不在 jar 里、编码对不对、占位符在不在」。
 * <p>
 * <b>为什么值得单独一条</b>：这三点都属于「编译与大多数单测都过、装到用户机器上才炸」的错误，
 * 而它炸掉的是整个功能（没有摘要指令 = 压缩不可用）。
 *
 * @author zcd
 */
@DisplayName("摘要指令资源")
class SummaryPromptTest {

    @Test
    @DisplayName("指令能从本插件的资源里读回来，且非空白")
    void load_should_readOwnResource() {
        SummaryPrompt prompt = SummaryPrompt.load();

        assertFalse(prompt.text().trim().isEmpty());
        // 中文读得回来才算 UTF-8 解码正确（平台默认编码不是 UTF-8 时这里会变成乱码）
        assertTrue(prompt.text().contains("摘要"), prompt.text());
    }

    @Test
    @DisplayName("指令必须带占位符：缺了它模型就不知道摘要该多长")
    void prompt_should_declareMaxCharsPlaceholder() {
        assertTrue(SummaryPrompt.load().text().contains(CompactionStrategy.MAX_CHARS_PLACEHOLDER),
                "摘要指令缺少占位符 " + CompactionStrategy.MAX_CHARS_PLACEHOLDER);
    }

    @Test
    @DisplayName("指令要求合并旧摘要：滚动摘要靠这一句才不丢信息")
    void prompt_should_askToMergePreviousSummary() {
        String text = SummaryPrompt.load().text();

        assertTrue(text.contains("旧摘要") || text.contains("已有摘要"), text);
    }

    @Test
    @DisplayName("资源缺失时直接抛：这个插件少了它就等于没装")
    void load_should_fail_when_resourceMissing() {
        // 资源路径是常量，无法在测试里改成不存在；这里改为钉住「失败必须抛异常」的契约：
        // 用一个必然读不到的位置验证 loadResource 的失败语义
        JellyfishException error = assertThrows(JellyfishException.class,
                () -> SummaryPrompt.loadFrom("not-here.md"));

        assertTrue(error.getMessage().contains("not-here.md"), error.getMessage());
    }
}
