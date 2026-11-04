package io.github.zcd0831.jellyfish.boot;

import io.github.zcd0831.jellyfish.application.action.PortRoutes;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationPort;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationRequest;
import io.github.zcd0831.jellyfish.application.action.port.ModelInvocationResult;
import io.github.zcd0831.jellyfish.application.action.port.ModelProfile;
import io.github.zcd0831.jellyfish.application.action.port.ToolCall;
import io.github.zcd0831.jellyfish.application.action.port.ModelUsage;
import io.github.zcd0831.jellyfish.application.planning.RunSessionResult;
import io.github.zcd0831.jellyfish.application.shared.ChunkSink;
import io.github.zcd0831.jellyfish.application.shared.Result;
import io.github.zcd0831.jellyfish.di.AppComponent;
import io.github.zcd0831.jellyfish.di.DaggerAppComponent;
import io.github.zcd0831.jellyfish.di.InfrastructureModule;
import io.github.zcd0831.jellyfish.di.ShellModule;
import io.github.zcd0831.jellyfish.domain.perception.session.Record;
import io.github.zcd0831.jellyfish.domain.shared.ModelId;
import io.github.zcd0831.jellyfish.domain.shared.SessionId;
import io.github.zcd0831.jellyfish.infrastructure.file.DataDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真装配的 smoke：临时数据目录 → 真 <code>DaggerAppComponent</code> → 跑完一轮，
 * 断言进度真的到了屏幕上、回复真的落进了流水。
 * <p>之前 boot 的 12 个测试类 43 条用例零走 <code>DaggerAppComponent</code>，
 * 把 <code>ShellModule.progressPort</code> 换成空实现照样全绿（R10 阻断 7）。
 * <p>规则出处：08-application §8.11（端口路由表是可替换点）。
 */
class BootSmokeTest {

    @TempDir
    Path tmp;

    private final ByteArrayOutputStream screen = new ByteArrayOutputStream();
    private final FakeModel model = new FakeModel();

    @Test
    void 真装配下跑完一轮_进度与回复都到屏幕() throws Exception {
        AppComponent component = component();

        Result<SessionId> started = component.startSessionUseCase().start(null, null, "做两件事");
        assertTrue(started.isOk(), () -> "启动会话失败：" + started.error().error().code());
        SessionId sessionId = started.value();
        model.script.clear();   // 生成标题的那次调用不算在这一轮里

        model.seed(toolCall("c1", "generate_plan",
                "{\"goal\":\"做两件事\",\"steps\":[{\"description\":\"第一步\",\"goal\":\"g\","
                        + "\"expectedOutcome\":\"o\",\"verificationCriteria\":\"c\"}]}"));
        model.seed(text("计划已创建"));
        model.seed(toolCall("c2", "submit_step", "{\"summary\":\"第一步做完了\"}"));
        // 检索代理等辅助调用也会走模型端口，尾部多备几次同样的回复
        model.seed(text("全部完成"));
        model.seed(text("全部完成"));
        model.seed(text("全部完成"));

        Result<RunSessionResult> result = component.runSessionUseCase().run(sessionId, null, ChunkSink.NOOP);

        assertTrue(result.isOk(), () -> "run 失败：" + result.error().error().code());
        assertEquals(RunSessionResult.Outcome.CONVERGED, result.value().outcome());

        String printed = screen();
        assertTrue(printed.contains("计划"), "进度要真的到屏幕上（把 progressPort 换空实现就该红）：" + printed);
        assertTrue(printed.contains("✓"), "步骤完成行要出现：" + printed);

        List<Record> records = component.recordStore().last(sessionId, 10);
        boolean answered = false;
        for (Record record : records) {
            answered = answered || "全部完成".equals(record.content());
        }
        assertTrue(answered, "模型回复要落进流水：" + records.size() + " 条");
    }

    /**
     * 冒烟断言是承重的：把进度端口换成空实现，屏幕上就没有计划行 —— 这条正是
     * R10 阻断 7 的实验（当时改坏装配，43 条 boot 用例全绿）。规则出处：08-application §8.11。
     */
    @Test
    void 进度端口换空实现就看不到计划行() throws Exception {
        AppComponent component = component();
        Map<String, Object> routes = new LinkedHashMap<String, Object>();
        routes.put("ModelInvocationPort", model);
        routes.put("ProgressPort", new io.github.zcd0831.jellyfish.application.shared.port.ProgressPort() {
            @Override
            public void emit(io.github.zcd0831.jellyfish.application.shared.ProgressEvent event) {
                // 空实现：什么都不显示
            }
        });
        component.portRoutes().replaceAll(routes);

        SessionId sessionId = component.startSessionUseCase().start(null, null, "做两件事").value();
        model.script.clear();
        model.seed(toolCall("c1", "generate_plan",
                "{\"goal\":\"做两件事\",\"steps\":[{\"description\":\"第一步\",\"goal\":\"g\","
                        + "\"expectedOutcome\":\"o\",\"verificationCriteria\":\"c\"}]}"));
        component.runSessionUseCase().run(sessionId, null, ChunkSink.NOOP);

        assertTrue(!screen().contains("计划"), "进度端口被换掉后屏幕不该有进度行，否则冒烟断言是空的：" + screen());
    }

    private AppComponent component() throws Exception {
        seedCatalog();
        PrintStream out = new PrintStream(screen, true, "UTF-8");
        AppComponent component = DaggerAppComponent.builder()
                .infrastructureModule(new InfrastructureModule(DataDirectory.of(tmp.toString())))
                .shellModule(new ShellModule(out, true))
                .build();
        // 装配之后换掉模型端口：这是插件与测试共用的一条既定缝（§8.11 端口路由表）
        Map<String, Object> routes = new LinkedHashMap<String, Object>();
        routes.put("ModelInvocationPort", model);
        PortRoutes portRoutes = component.portRoutes();
        portRoutes.replaceAll(routes);
        return component;
    }

    /** 真装配要能从数据目录里读到供应商与模型，否则连会话都开不起来。 */
    private void seedCatalog() throws Exception {
        java.nio.file.Files.createDirectories(tmp.resolve("providers"));
        java.nio.file.Files.createDirectories(tmp.resolve("models"));
        writeFile("providers/p1.json", "{\"id\":\"p1\",\"name\":\"fake\","
                + "\"apiEndpoint\":\"http://localhost\",\"protocol\":\"OPENAI_CHAT\","
                + "\"credential\":{\"apiKeyRef\":\"FAKE_KEY\"},\"status\":\"ACTIVE\"}");
        writeFile("models/m1.json", "{\"id\":\"m1\",\"providerId\":\"p1\",\"modelName\":\"fake-model\","
                + "\"status\":\"ACTIVE\",\"contextLimit\":{\"maxInputTokens\":8000,"
                + "\"maxOutputTokens\":4096},\"capabilities\":[]}");
    }

    private void writeFile(String relative, String text) throws Exception {
        java.nio.file.Files.write(tmp.resolve(relative), text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String screen() throws UnsupportedEncodingException {
        return screen.toString("UTF-8");
    }

    private static ModelInvocationResult text(String text) {
        return new ModelInvocationResult(text, Collections.<ToolCall>emptyList(),
                new ModelUsage(5, 2), ModelId.of("m1"));
    }

    private static ModelInvocationResult toolCall(String id, String name, String arguments) {
        return new ModelInvocationResult(null,
                Collections.singletonList(new ToolCall(id, name, arguments)),
                new ModelUsage(5, 2), ModelId.of("m1"));
    }

    /** 脚本化模型：按顺序返回预设结果。 */
    private static final class FakeModel implements ModelInvocationPort {

        private final Deque<ModelInvocationResult> script = new ArrayDeque<ModelInvocationResult>();
        private final List<ModelInvocationRequest> requests = new ArrayList<ModelInvocationRequest>();

        void seed(ModelInvocationResult result) {
            script.add(result);
        }

        @Override
        public ModelInvocationResult invoke(ModelInvocationRequest request) {
            requests.add(request);
            ModelInvocationResult next = script.poll();
            return next == null ? text("（脚本用尽）") : next;
        }

        @Override
        public ModelInvocationResult invokeStreaming(ModelInvocationRequest request, ChunkSink sink) {
            return invoke(request);
        }

        @Override
        public ModelProfile describe(ModelId modelId) {
            return new ModelProfile(80000, 4096);
        }
    }
}
