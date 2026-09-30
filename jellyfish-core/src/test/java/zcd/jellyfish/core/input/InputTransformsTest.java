package zcd.jellyfish.core.input;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zcd.jellyfish.api.event.RegisterOptions;
import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.InputTransformRequest;
import zcd.jellyfish.api.extension.InputTransformResult;
import zcd.jellyfish.infra.extension.ExtensionRegistry;
import zcd.jellyfish.infra.registry.TypeRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InputTransforms} 的单元测试：验证链式传递、短路与失败语义。
 * <p>
 * 用真实 {@code ExtensionRegistry}，因此「order 升序」「0 handler」这两条走的是与生产同一条查找路径。
 *
 * @author zcd
 */
@DisplayName("InputTransforms 输入改写服务")
class InputTransformsTest {

    /** 真实同步扩展点策略。 */
    private ExtensionRegistry extensions;

    /** 被测服务。 */
    private InputTransforms transforms;

    @BeforeEach
    void setUp() {
        extensions = new ExtensionRegistry(new TypeRegistry());
        transforms = new InputTransforms(extensions);
    }

    @Test
    void transform_should_return_continueAsIs_when_no_handler_registered() {
        // 0 handler 是兼容性承诺：外壳据此原样沿用它自己手里那份文本，行为与引入本扩展点之前逐字节一致
        InputTransformResult result = transforms.transform("s1", "原文", InputTransformRequest.Source.CLI);

        assertFalse(result.isHandled());
        assertFalse(result.hasText());
        assertNull(result.getText());
    }

    @Test
    void transform_should_apply_replacements_in_order() {
        // Given：后者看到的是前者产出的文本
        List<String> seen = new ArrayList<String>();
        register(1, request -> {
            seen.add(request.getText());
            return InputTransformResult.replace(request.getText() + "-1");
        });
        register(2, request -> {
            seen.add(request.getText());
            return InputTransformResult.replace(request.getText() + "-2");
        });

        // When
        InputTransformResult result = transforms.transform("s1", "原文", InputTransformRequest.Source.TUI);

        // Then
        assertEquals(Arrays.asList("原文", "原文-1"), seen);
        assertTrue(result.hasText());
        assertEquals("原文-1-2", result.getText());
    }

    @Test
    void transform_should_keep_current_text_when_handler_abstains() {
        // Given：第一个改、第二个不表态——当前值必须保留，而不是退回原文
        register(1, request -> InputTransformResult.replace("改过"));
        register(2, request -> InputTransformResult.continueAsIs());

        // When
        InputTransformResult result = transforms.transform("s1", "原文", InputTransformRequest.Source.TUI);

        // Then
        assertEquals("改过", result.getText());
    }

    @Test
    void transform_should_shortCircuit_when_handler_handles() {
        // Given：接过去之后就没有文本可改了，后面的处理器不该再被调用
        List<String> called = new ArrayList<String>();
        register(1, request -> {
            called.add("first");
            return InputTransformResult.handled("我自己回答");
        });
        register(2, request -> {
            called.add("second");
            return InputTransformResult.continueAsIs();
        });

        // When
        InputTransformResult result = transforms.transform("s1", "?help", InputTransformRequest.Source.TUI);

        // Then
        assertEquals(Arrays.asList("first"), called);
        assertTrue(result.isHandled());
        assertEquals("我自己回答", result.getNotice());
        assertFalse(result.hasText());
    }

    @Test
    void transform_should_keep_current_text_when_handler_throws() {
        // 一个坏插件不该让用户敲完回车之后什么都发不出去
        register(1, request -> {
            throw new IllegalStateException("插件崩了");
        });
        register(2, request -> InputTransformResult.replace("第二个生效"));

        InputTransformResult result = transforms.transform("s1", "原文", InputTransformRequest.Source.CLI);

        assertEquals("第二个生效", result.getText());
    }

    @Test
    void transform_should_report_no_session_on_home_page() {
        // 首页还没有会话：请求要如实回答 hasSession 为假，且 sessionId 为 null 时不能炸
        List<String> seen = new ArrayList<String>();
        register(1, request -> {
            seen.add(String.valueOf(request.hasSession()) + "/" + request.getSessionId());
            return InputTransformResult.continueAsIs();
        });

        transforms.transform(null, "第一条输入", InputTransformRequest.Source.TUI);

        assertEquals(Arrays.asList("false/null"), seen);
    }

    @Test
    void transform_should_report_source_to_handler() {
        List<String> seen = new ArrayList<String>();
        register(1, request -> {
            seen.add(request.getSource().name());
            return InputTransformResult.continueAsIs();
        });

        transforms.transform("s1", "a", InputTransformRequest.Source.CLI);
        transforms.transform("s1", "b", InputTransformRequest.Source.TUI);
        transforms.transform("s1", "c", InputTransformRequest.Source.SERVER);

        assertEquals(Arrays.asList("CLI", "TUI", "SERVER"), seen);
    }

    /**
     * 注册一个输入改写处理器。
     *
     * @param order   顺序
     * @param handler 处理器
     */
    private void register(int order, ExtensionHandler<InputTransformRequest, InputTransformResult> handler) {
        extensions.contribute("test", InputTransformRequest.class, null, handler, RegisterOptions.order(order));
    }
}
