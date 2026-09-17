package zcd.jellyfish.plugin.python;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.PluginState;
import zcd.jellyfish.api.JellyfishException;
import zcd.jellyfish.api.extension.CommandRequest;
import zcd.jellyfish.api.extension.CommandResult;
import zcd.jellyfish.api.extension.PromptContributionRequest;
import zcd.jellyfish.api.extension.ToolCallRequest;
import zcd.jellyfish.api.extension.ToolCallResult;
import zcd.jellyfish.infra.plugin.PF4JPluginManager;
import zcd.jellyfish.infra.plugin.PluginContextFactory;
import zcd.jellyfish.infra.plugin.PluginRuntimeConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实 Python 的端到端测试：注册 → 调用 → 超时隔离 → 关闭。
 * <p>
 * <b>为什么不进 {@code mvn test}</b>：它需要一个真实的解释器，而 AGENTS.md 规定单元测试不访问
 * 外部资源。因此它命名为 {@code *IT}（Surefire 默认的包含规则只认 {@code *Test}），
 * 由 {@code -Pscript-it} profile 显式启用。这一步提前到 P4 落地，是因为网关、worker 与 SDK
 * 都是「没有测试守着就会悄悄坏掉」的代码——它们唯一能被验证的方式就是真的跑一次。
 * <p>
 * 机器上没装解释器时整类跳过（{@code assumeTrue}），而不是失败：环境缺失与代码有 bug 是两件事。
 *
 * @author zcd
 */
@DisplayName("Python 脚本端到端")
class PythonScriptIT {

    /** 解释器探测超时。 */
    private static final long PROBE_TIMEOUT_SECONDS = 5L;

    /** 插件根目录。 */
    @TempDir
    Path pluginsRoot;

    /** 脚本根目录。 */
    @TempDir
    Path scriptsRoot;

    /** 网关资源抽取根目录。 */
    @TempDir
    Path gatewayRoot;

    /** PID 文件目录；固定到临时目录，避免测试往用户主目录写东西。 */
    @TempDir
    Path pidRoot;

    /** 插件管理器。 */
    private PF4JPluginManager manager;

    /** 共用注册表：注册与调用必须落在同一份上，否则测试会「注册成功但调用找不到」。 */
    private final zcd.jellyfish.infra.registry.TypeRegistry registry =
            new zcd.jellyfish.infra.registry.TypeRegistry();

    /** 同步扩展点策略。 */
    private final zcd.jellyfish.infra.extension.ExtensionRegistry extensions =
            new zcd.jellyfish.infra.extension.ExtensionRegistry(registry);

    /** 事件通道。 */
    private final zcd.jellyfish.infra.event.EventChannel events =
            new zcd.jellyfish.infra.event.EventChannel(
                    zcd.jellyfish.infra.event.EventChannelOptions.defaults(), registry);

    /**
     * 跳过没有解释器的环境。
     */
    @BeforeEach
    void requirePython() {
        assumeTrue(interpreterAvailable(), "本机没有可用的 python3，跳过端到端测试");
        // 事件桥接的用例要求通道真的在派发：未 start 的通道只把通知塞进启动期缓冲，
        // 于是「脚本没收到事件」会表现为一个与代码无关的谜题
        events.start();
    }

    /**
     * 关闭运行时，确保子进程不残留。
     */
    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
            manager = null;
        }
        // 事件通道要显式关闭：它是自己的线程池，留着会让下一个用例看到上一轮的通知
        events.close();
    }

    @Test
    @DisplayName("工具应被真实执行，结果与脚本返回一致")
    void tool_should_returnScriptOutput_when_invokedEndToEnd() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime();

        ToolCallResult result = invokeTool("jira_issue",
                Collections.<String, Object>singletonMap("key", "PROJ-1"));

        assertEquals("issue PROJ-1 处于 OPEN（会话 s-1）", result.getOutput());
    }

    @Test
    @DisplayName("脚本抛出的异常应变成可读的调用失败，而不是静默的空结果")
    void tool_should_failWithMessage_when_scriptRaises() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime();

        JellyfishException failure = org.junit.jupiter.api.Assertions.assertThrows(
                JellyfishException.class,
                () -> invokeTool("jira_issue", Collections.<String, Object>emptyMap()));

        assertTrue(failure.getMessage().contains("缺少参数 key"), failure.getMessage());
    }

    @Test
    @DisplayName("类型级贡献应能按类型调用")
    void contribution_should_beInvoked_byType() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime();

        zcd.jellyfish.api.extension.PromptContribution contribution =
                extensions.invoke(extensions.handler(PromptContributionRequest.class, null),
                        new PromptContributionRequest("s-1"));

        assertEquals("Jira：会话 s-1 有 3 个未读", contribution.getText());
    }

    @Test
    @DisplayName("命令应被真实执行，结果带命令名与输出")
    void command_should_beInvoked_endToEnd() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime();

        CommandResult result = extensions.invoke(extensions.handler(CommandRequest.class, "jira"),
                new CommandRequest("jira", null, "s-1"));

        assertEquals("已切换 PROJ-1", result.getOutput());
    }

    @Test
    @DisplayName("卡死的脚本应在超时后被隔离，且下一次调用能重新拉起")
    void tool_should_beIsolatedAfterTimeout_thenRecover() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime(2);

        // 卡死的调用必须在超时后失败——而不是永远挂着（那会把 ReAct 回合也一起拖住）。
        // 两侧的超时值相同，因此**谁先发现就由谁报**：宿主可能报本地超时，
        // 也可能先收到网关的「已隔离」。这个不确定性是设计使然（而不是测试写得松），
        // 它会在 P5 落地「宿主超时 → 主动 kill_worker」之后收敛成单一来源
        long started = System.currentTimeMillis();
        JellyfishException failure = org.junit.jupiter.api.Assertions.assertThrows(
                JellyfishException.class,
                () -> invokeTool("jira_hang", Collections.<String, Object>emptyMap()));
        assertTrue(System.currentTimeMillis() - started >= 2000, "不应早于配置的超时返回");
        boolean timeoutReported = failure instanceof zcd.jellyfish.script.protocol.ScriptTimeoutException
                || failure.getMessage().contains("隔离");
        assertTrue(timeoutReported, failure.getMessage());

        // 隔离只针对该脚本的那一代 worker：重新拉起之后调用应当恢复。
        // 这里给几秒重试窗口而不是立刻断言，因为「隔离」是两段式的：请求先被拒绝，
        // 卡死的 worker 随后被终止（它可能正卡在不响应信号的系统调用里），
        // 新 worker 就绪之前的那一小段时间里调用仍会失败——这是**已实现行为**，不是抖动
        ToolCallResult recovered = invokeUntilSucceeds("jira_issue",
                Collections.<String, Object>singletonMap("key", "X"), 15_000L);
        assertEquals("issue X 处于 OPEN（会话 s-1）", recovered.getOutput());
    }

    @Test
    @DisplayName("清单与实现不一致时脚本应拒绝服务，且错误可归因为清单问题")
    void script_should_refuseService_when_manifestLies() throws IOException {
        // 清单里多声明了一个代码没有的工具：这正是「模型按不存在的定义去调用」的成因
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST.replace(
                "\"tools\":[", "\"tools\":[{\"name\":\"jira_nonexistent\"},"));
        startRuntime();

        JellyfishException failure = org.junit.jupiter.api.Assertions.assertThrows(
                JellyfishException.class,
                () -> invokeTool("jira_nonexistent", Collections.<String, Object>emptyMap()));

        // 错误必须点名「哪个脚本、差在哪一项」：只说「初始化失败」等于把定位工作推回给用户，
        // 而这两条信息此刻正好都在网关手里
        assertTrue(failure.getMessage().contains("jira_nonexistent"), failure.getMessage());
        assertTrue(failure.getMessage().contains("tools"), failure.getMessage());
    }

    @Test
    @DisplayName("状态命令应列出脚本与已登记能力")
    void statusCommand_should_listScripts() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime();

        CommandResult result = extensions.invoke(extensions.handler(CommandRequest.class, "python"),
                new CommandRequest("python", null, null));

        assertTrue(result.getOutput().contains("脚本 1 个"), result.getOutput());
        assertTrue(result.getOutput().contains("运行时"), result.getOutput());
    }


    @Test
    @DisplayName("空闲的 worker 应自行退场，进程数回到只剩网关")
    void worker_should_selfDestruct_when_idle() throws IOException, InterruptedException {
        // 「空闲时进程数回零」是本方案明确承诺的性质（懒启动 + 自毁），而它依赖 worker 与网关
        // 两侧各自的周期检查。这里用操作系统的进程表直接验证，而不是看日志——
        // 后者只能证明「它说要退场」，前者才能证明「它真的退了」
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        java.nio.file.Path gatewayDirectory = new zcd.jellyfish.script.GatewayResources(gatewayRoot)
                .materialize(new PythonLanguage(interpreter()), PythonLanguage.GATEWAY_RESOURCES);
        startRuntimeWithIdle(1);
        invokeTool("jira_issue", Collections.<String, Object>singletonMap("key", "K"));

        assertTrue(awaitProcessCount(gatewayDirectory, 2), "调用之后应有一个 worker 在跑");
        assertTrue(awaitProcessCount(gatewayDirectory, 1),
                "空闲超过配置时长后 worker 应自行退场，实际仍有 "
                        + countProcesses(gatewayDirectory) + " 个进程");
    }


    @Test
    @DisplayName("熔断只影响出问题的脚本，且不摘注册：同语言其它脚本照常工作")
    void circuit_should_isolateOnlyTheBrokenScript() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        writeScript("git", GIT_SCRIPT, GIT_MANIFEST);
        // 一次超时就打开、冷却 1 秒：把「熔断 — 拒绝 — 冷却 — 恢复」压缩到用例能跑的时长里
        startRuntimeWithCircuit(2, 1, 1, 5);

        assertThrows(JellyfishException.class,
                () -> invokeTool("jira_hang", Collections.<String, Object>emptyMap()));

        // 同一个网关、同一个语言下的另一个脚本完全不受影响——这正是「每脚本一 worker」的直接收益
        ToolCallResult other = invokeTool("git_status", Collections.<String, Object>emptyMap());
        assertEquals("main 干净", other.getOutput());

        // 熔断不摘注册：工具还在清单里，但调用被**立即**拒绝，且文案带剩余时间。
        // 「立即」是这条断言的全部价值——同一个工具在没有熔断时会正常返回，
        // 因此耗时能证明它是被拒绝的，而不是被执行的
        long started = System.currentTimeMillis();
        JellyfishException refusal = assertThrows(JellyfishException.class,
                () -> invokeTool("jira_issue", Collections.<String, Object>singletonMap("key", "X")));
        assertTrue(System.currentTimeMillis() - started < 1000,
                "拒绝应发生在派发之前，而不是等一个超时");
        assertTrue(refusal.getMessage().contains("熔断"), refusal.getMessage());

        String status = statusCommand();
        // 台账按脚本名排序，而这两个名字的先后是 `git` 在前——因此断言的是片段而不是整行
        assertTrue(status.contains("jira 熔断中"), status);
        assertTrue(status.contains("git 正常"), status);
    }

    @Test
    @DisplayName("冷却到期后应自动半开并恢复，全程不需要 /reload")
    void circuit_should_recoverAutomatically_afterCooldown() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntimeWithCircuit(2, 1, 1, 5);

        assertThrows(JellyfishException.class,
                () -> invokeTool("jira_hang", Collections.<String, Object>emptyMap()));

        // 「自动恢复」是这个机制存在的理由：摘注册的代价就是恢复必须走 /reload，
        // 而这里什么都不做，只是等过冷却
        ToolCallResult recovered = invokeUntilSucceeds("jira_issue",
                Collections.<String, Object>singletonMap("key", "X"), 20_000L);

        assertEquals("issue X 处于 OPEN（会话 s-1）", recovered.getOutput());
        assertTrue(statusCommand().contains("jira 正常"), statusCommand());
    }


    @Test
    @DisplayName("超时配 0 表示不超时：调用应正常完成，而不是被当成立即超时秒杀")
    void invokeTimeout_should_meanNoTimeout_whenConfiguredAsZero() throws IOException {
        // 这个用例钉的是一处**字面意思与实现相反**的缺陷：网关最初把 0 当成截止时间，
        // 于是 `now > deadline` 恒真，任何调用都在派发的下一拍被隔离——
        // 现象是「脚本永远跑不出结果」，而用户从配置里读到的却是「不超时」
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime(0);

        ToolCallResult result = invokeTool("jira_issue",
                Collections.<String, Object>singletonMap("key", "K"));

        assertEquals("issue K 处于 OPEN（会话 s-1）", result.getOutput());
    }

    @Test
    @DisplayName("网关被强杀后，卡在用户代码里的 worker 也应自行退出（不留孤儿）")
    void worker_should_notSurvive_when_gatewayIsKilledFiercely()
            throws IOException, InterruptedException {
        // 这是最难清理的一种孤儿：worker 卡在用户代码里，既读不到协议套接字的 EOF，
        // 也轮不到事件循环里的「父进程没了」检查。它只能靠 worker 侧的定时器看门狗发现，
        // 因此这里用操作系统的进程表直接断言——先让它真的卡住，再强杀网关
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        java.nio.file.Path gatewayDirectory = new zcd.jellyfish.script.GatewayResources(gatewayRoot)
                .materialize(new PythonLanguage(interpreter()), PythonLanguage.GATEWAY_RESOURCES);
        startRuntime(30);
        invokeTool("jira_issue", Collections.<String, Object>singletonMap("key", "K"));
        assertTrue(awaitProcessCount(gatewayDirectory, 2), "应先有一个 worker 在跑");

        // 让脚本卡死：它会在 time.sleep 里待十分钟，期间不会回到事件循环
        Thread hanging = new Thread(() -> {
            try {
                invokeTool("jira_hang", Collections.<String, Object>emptyMap());
            } catch (JellyfishException expected) {
                // 网关会被杀，这次调用必然失败；这里只关心进程是否残留
            }
        });
        hanging.setDaemon(true);
        hanging.start();
        Thread.sleep(1000L);

        Process kill = new ProcessBuilder("pkill", "-9", "-f", gatewayDirectory.toString()).start();
        assertEquals(0, kill.waitFor(), "强杀网关的命令应成功");

        assertTrue(awaitProcessCount(gatewayDirectory, 0),
                "网关被强杀后不该留下任何进程，实际仍有 "
                        + countProcesses(gatewayDirectory) + " 个");
        hanging.join(5000L);
    }


    @Test
    @DisplayName("PID 文件应记下网关自己的 PID，并在正常退出时删掉")
    void pidFile_should_recordGatewayPid_andRemoveIt_whenGatewayExits() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime();

        invokeTool("jira_issue", Collections.<String, Object>singletonMap("key", "K"));

        int pid = readPid(pidFile());
        assertTrue(pid > 0, "PID 文件里应是一个正整数，实际为 " + pid);
        assertTrue(statusCommand().contains("网关 PID " + pid), statusCommand());

        // 正常退出（宿主关闭网关）时必须删掉：留着它会让下一次启动把一个还活着的 PID 报成遗留
        manager.close();
        manager = null;
        assertFalse(Files.exists(pidFile()), "正常退出后 PID 文件不应残留");
    }

    @Test
    @DisplayName("启动时发现的遗留 PID 只报告不处置：文件里那个进程必须活着")
    void stalePidFile_should_beReportedWithoutKilling_theRecordedProcess()
            throws IOException, InterruptedException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        // 造一个「上一任网关」：用 sh 写下自己的 PID 再 exec 成 sleep，
        // 这样一个真实存活的进程就占了那个 PID，且它不属于本 JVM（不会被任何回收路径带走）
        Files.createDirectories(pidRoot);
        Path marker = pidRoot.resolve("sleeper.pid");
        Process sleeper = new ProcessBuilder("sh", "-c", "echo $$ > " + marker + "; exec sleep 120")
                .start();
        try {
            int stalePid = awaitPid(marker, 5000L);
            assertTrue(sleeper.isAlive(), "造出来的「上一任网关」应先真的活着");
            Files.write(pidFile(), (stalePid + "\n").getBytes(StandardCharsets.UTF_8));

            startRuntime();
            invokeTool("jira_issue", Collections.<String, Object>singletonMap("key", "K"));

            // 「只报告」：报告必须能被人看见（台账输出），而不是只进日志
            String status = statusCommand();
            assertTrue(status.contains("PID " + stalePid + "（仍存活）"), status);
            // 「不处置」：那个进程必须原封不动地活着，且文件已被本代网关接管
            assertTrue(sleeper.isAlive(), "遗留 PID 文件里的进程被误杀了");
            assertTrue(readPid(pidFile()) != stalePid, "PID 文件应被本代网关接管");
        } finally {
            sleeper.destroyForcibly();
            if (!sleeper.waitFor(10, TimeUnit.SECONDS) && Files.exists(marker)) {
                // 兜底：正常情况下 destroyForcibly 就够；这里用记录下来的 PID 再杀一次，
                // 免得一个卡住的 sleep 留到下一次全量测试里变成“背景噪声”
                new ProcessBuilder("kill", "-9", String.valueOf(readPid(marker)))
                        .start().waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @DisplayName("网关被强杀后 PID 文件应留下来，充当事后排查的线索")
    void pidFile_should_survive_whenGatewayIsKilledFiercely() throws IOException, InterruptedException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        java.nio.file.Path gatewayDirectory = new zcd.jellyfish.script.GatewayResources(gatewayRoot)
                .materialize(new PythonLanguage(interpreter()), PythonLanguage.GATEWAY_RESOURCES);
        startRuntime(30);
        invokeTool("jira_issue", Collections.<String, Object>singletonMap("key", "K"));
        int killedPid = readPid(pidFile());

        Process kill = new ProcessBuilder("pkill", "-9", "-f", gatewayDirectory.toString()).start();
        assertEquals(0, kill.waitFor(), "强杀网关的命令应成功");

        // 强杀后没有人会去删它，而且这正是它的全部价值：下一次启动能从里面读到那个死掉的 PID
        assertEquals(killedPid, readPid(pidFile()), "强杀后 PID 文件应保留原内容");
        invokeUntilSucceeds("jira_issue", Collections.<String, Object>singletonMap("key", "K"), 15_000L);
        String status = statusCommand();
        assertTrue(status.contains("PID " + killedPid + "（已不存在）"), status);
    }

    @Test
    @DisplayName("台账应报出 worker 的 PID 与就绪状态")
    void statusCommand_should_showWorkerPid_afterInvoke() throws IOException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime();

        invokeTool("jira_issue", Collections.<String, Object>singletonMap("key", "K"));

        String status = statusCommand();
        assertTrue(status.contains("worker "), status);
        assertTrue(status.contains("ready"), status);
        // PID 是排查时唯一需要手动输入的东西，而它只有进程侧知道
        assertTrue(status.contains("pid="), status);
    }

    @Test
    @DisplayName("脚本卡住时台账应报出「在途」——这是「为什么这个工具很慢」的直接答案")
    void statusCommand_should_showInflight_whileScriptIsBusy()
            throws IOException, InterruptedException {
        writeScript("jira", TOOL_SCRIPT, FULL_MANIFEST);
        startRuntime(2);

        Thread hanging = new Thread(() -> {
            try {
                invokeTool("jira_hang", Collections.<String, Object>emptyMap());
            } catch (JellyfishException expected) {
                // 这个调用注定超时，这里只关心超时之前台账能看到什么
            }
        });
        hanging.setDaemon(true);
        hanging.start();
        Thread.sleep(700L);

        String status = statusCommand();
        assertTrue(status.contains("在途=1"), status);

        // 超时之后 worker 被隔离，台账必须跟着变成「已退出」而不是继续报就绪：
        // 这两个数字都是网关推过来的快照，推不出去就是永久陈旧
        hanging.join(15_000L);
        assertTrue(awaitStatus("exited", 10_000L), statusCommand());
    }

    /**
     * 等到台账里出现某个字样。
     *
     * @param marker    待查找的字样
     * @param timeoutMs 等待上限
     * @return 等到了返回 {@code true}
     * @throws InterruptedException 等待被中断时抛出
     */
    private boolean awaitStatus(String marker, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (statusCommand().contains(marker)) {
                return true;
            }
            Thread.sleep(200L);
        }
        return false;
    }

    @Test
    @DisplayName("内核事件应送达订阅它的脚本")
    void event_should_reachScript_when_kernelPublishes() throws IOException {
        // 这条链路跨了四个进程内/进程外的边界：EventChannel 通知线程 → 桥接队列 →
        // 桥接推送线程 → 网关 → worker → SDK 分发。任何一段写错都表现为「处理器不执行」，
        // 因此这里断言的是**最终落在磁盘上的那条记录**
        writeScript("jira", EVENT_SCRIPT, EVENT_MANIFEST);
        startRuntime();
        invokeTool("publish", Collections.<String, Object>emptyMap());
        writeEventLog("jira", "");

        events.publish(new zcd.jellyfish.api.event.notification.ConfigWarningEvent(
                "unit-test", "配置有问题"));

        assertTrue(awaitEventLog("jira", "warning:unit-test:配置有问题"),
                "事件未送达脚本，实际记录: " + readEventLog("jira"));
    }

    @Test
    @DisplayName("脚本发布的事件应进入内核事件通道，且不回声给发布者自己")
    void emit_should_publishEvent_and_notEchoBackToPublisher() throws IOException {
        writeScript("jira", EVENT_SCRIPT, EVENT_MANIFEST);
        startRuntime();
        invokeTool("publish", Collections.<String, Object>emptyMap());
        writeEventLog("jira", "");

        java.util.List<zcd.jellyfish.api.event.notification.PluginNotificationEvent> received =
                Collections.synchronizedList(
                        new java.util.ArrayList<zcd.jellyfish.api.event.notification.PluginNotificationEvent>());
        events.subscribe("it", zcd.jellyfish.api.event.notification.PluginNotificationEvent.class,
                received::add);

        invokeTool("publish", Collections.<String, Object>emptyMap());

        long deadline = System.currentTimeMillis() + 10_000L;
        while (received.isEmpty() && System.currentTimeMillis() < deadline) {
            sleepQuietly(100L);
        }
        assertEquals(1, received.size(), "脚本发布的事件应恰好到达内核一次");
        assertEquals("script:jira", received.get(0).getSource());

        // 回声：脚本自己也订阅了这个事件，但它不该收到自己刚发的那个。
        // 等一小会儿再断言，因为「没收到」只能通过时间来观察
        sleepQuietly(500L);
        assertFalse(readEventLog("jira").contains("echo:"),
                "脚本收到了自己发布的事件（回声），实际记录: " + readEventLog("jira"));
    }

    @Test
    @DisplayName("发布不可发布的事件应被拒绝，且不影响脚本继续服务")
    void emit_should_beRejected_when_eventIsNotEmittable() throws IOException {
        writeScript("jira", EVENT_SCRIPT, EVENT_MANIFEST);
        startRuntime();
        invokeTool("publish", Collections.<String, Object>emptyMap());
        writeEventLog("jira", "");

        ToolCallResult result = invokeTool("publish_forbidden", Collections.<String, Object>emptyMap());

        // 拒绝是**正常结论**而不是故障：脚本这次调用照样成功返回，
        // 这正是「脚本不能伪造内核语义事件」在没有抛出异常的情况下生效的样子
        assertEquals("attempted", result.getOutput());
        sleepQuietly(500L);
        assertFalse(readEventLog("jira").contains("warning:"),
                "不可发布的事件不该进入内核，实际记录: " + readEventLog("jira"));
    }

    /**
     * 执行一次状态命令。
     *
     * @return 命令输出
     */
    private String statusCommand() {
        return extensions.invoke(extensions.handler(CommandRequest.class, "python"),
                new CommandRequest("python", null, null)).getOutput();
    }

    /**
     * 本用例组使用的 PID 文件路径。
     *
     * @return 路径
     */
    private Path pidFile() {
        return pidRoot.resolve("script-python.pid");
    }

    /**
     * 读一个 PID 文件。
     *
     * @param file 文件路径
     * @return PID
     * @throws IOException 读取失败时抛出
     */
    private static int readPid(Path file) throws IOException {
        return Integer.parseInt(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim());
    }

    /**
     * 等到 PID 标记文件出现并读出内容。
     *
     * @param marker   标记文件
     * @param timeoutMs 等待上限
     * @return PID
     * @throws IOException          读取失败时抛出
     * @throws InterruptedException 等待被中断时抛出
     */
    private static int awaitPid(Path marker, long timeoutMs) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(marker) && Files.size(marker) > 0) {
                return readPid(marker);
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("没有等到 PID 标记文件: " + marker);
    }


    /**
     * 事件记录文件路径。
     *
     * @param scriptId 脚本目录名
     * @return 记录文件路径
     */
    private Path eventLog(String scriptId) {
        return scriptsRoot.resolve(scriptId).resolve("events.log");
    }

    /**
     * 覆盖写入事件记录文件。
     * <p>
     * 每个用例开头清空一次：事件是异步的，上一个用例的残留会让断言「看起来通过了」。
     *
     * @param scriptId 脚本目录名
     * @param content  初始内容
     * @throws IOException 写入失败时抛出
     */
    private void writeEventLog(String scriptId, String content) throws IOException {
        Files.write(eventLog(scriptId), content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 读取事件记录文件。
     *
     * @param scriptId 脚本目录名
     * @return 文件内容；文件不存在时返回空串
     */
    private String readEventLog(String scriptId) {
        try {
            Path path = eventLog(scriptId);
            return Files.exists(path) ? new String(Files.readAllBytes(path), StandardCharsets.UTF_8) : "";
        } catch (IOException error) {
            return "";
        }
    }

    /**
     * 等到事件记录里出现某段文本。
     *
     * @param scriptId 脚本目录名
     * @param expected 期望片段
     * @return 出现返回 {@code true}
     */
    private boolean awaitEventLog(String scriptId, String expected) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (readEventLog(scriptId).contains(expected)) {
                return true;
            }
            sleepQuietly(100L);
        }
        return false;
    }

    /**
     * 安静地睡一会儿（中断只恢复标志位）。
     *
     * @param millis 毫秒
     */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 启动插件并指定熔断参数。
     * <p>
     * <b>为什么探测轮数要放宽</b>：冷却只有 1 秒，而被隔离的 worker 要几秒才真正退场
     * （它可能卡在不响应信号的系统调用里，只能等强杀）。若用默认的 3 轮，用例可能把
     * 「新 worker 还没就绪」连着记成 3 轮探测失败、直接转永久——那是配置过紧，不是被测行为错了。
     *
     * @param invokeTimeoutSeconds 单次调用超时秒数
     * @param failuresToOpen       连续失败多少次打开熔断
     * @param cooldownSeconds      冷却秒数
     * @param roundsToPermanent    探测失败几轮转永久
     * @throws IOException 安装插件失败时抛出
     */
    private void startRuntimeWithCircuit(int invokeTimeoutSeconds, int failuresToOpen,
                                         int cooldownSeconds, int roundsToPermanent) throws IOException {
        installPlugin();
        Map<String, Object> breaker = new LinkedHashMap<String, Object>();
        breaker.put(PythonConfig.KEY_FAILURES_TO_OPEN, Integer.valueOf(failuresToOpen));
        breaker.put(PythonConfig.KEY_COOLDOWN_SECONDS, Integer.valueOf(cooldownSeconds));
        breaker.put(PythonConfig.KEY_ROUNDS_TO_PERMANENT, Integer.valueOf(roundsToPermanent));
        Map<String, Object> python = new LinkedHashMap<String, Object>();
        python.put(PythonConfig.KEY_SCRIPTS_ROOT, scriptsRoot.toString());
        python.put(PythonConfig.KEY_GATEWAY_ROOT, gatewayRoot.toString());
        python.put(PythonConfig.KEY_INVOKE_TIMEOUT, Integer.valueOf(invokeTimeoutSeconds));
        python.put(PythonConfig.KEY_PYTHON_PATH, interpreter());
        python.put(PythonConfig.KEY_PID_DIRECTORY, pidRoot.toString());
        python.put(PythonConfig.KEY_CIRCUIT_BREAKER, breaker);
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<String, Map<String, Object>>();
        configurations.put("jellyfish-plugin-python", python);
        manager = new PF4JPluginManager(new PluginContextFactory(extensions, events, registry),
                new PluginRuntimeConfig(Collections.singletonList(pluginsRoot), null, null, configurations));
        manager.bootstrap();
        assertEquals(PluginState.STARTED, manager.stateOf("jellyfish-plugin-python"));
    }

    /**
     * 用默认超时启动插件，并指定 worker 空闲自毁秒数。
     *
     * @param idleSeconds worker 空闲自毁秒数
     * @throws IOException 安装插件失败时抛出
     */
    private void startRuntimeWithIdle(int idleSeconds) throws IOException {
        installPlugin();
        Map<String, Object> python = new LinkedHashMap<String, Object>();
        python.put(PythonConfig.KEY_SCRIPTS_ROOT, scriptsRoot.toString());
        python.put(PythonConfig.KEY_GATEWAY_ROOT, gatewayRoot.toString());
        python.put(PythonConfig.KEY_PYTHON_PATH, interpreter());
        python.put(PythonConfig.KEY_PID_DIRECTORY, pidRoot.toString());
        python.put(PythonConfig.KEY_WORKER_IDLE, Integer.valueOf(idleSeconds));
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<String, Map<String, Object>>();
        configurations.put("jellyfish-plugin-python", python);
        manager = new PF4JPluginManager(new PluginContextFactory(extensions, events, registry),
                new PluginRuntimeConfig(Collections.singletonList(pluginsRoot), null, null, configurations));
        manager.bootstrap();
    }

    /**
     * 数一数某个网关目录下还有几个进程（网关自己 + 它的 worker，二者的命令行相同）。
     *
     * @param gatewayDirectory 网关资源目录
     * @return 进程数
     * @throws IOException          命令执行失败时抛出
     * @throws InterruptedException 等待被中断时抛出
     */
    private static int countProcesses(java.nio.file.Path gatewayDirectory)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder("ps", "-eo", "command").redirectErrorStream(true).start();
        String output = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8);
        process.waitFor(5, TimeUnit.SECONDS);
        int count = 0;
        for (String line : output.split("\n")) {
            if (line.contains(gatewayDirectory.toString())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 等到进程数落到期望值。
     *
     * @param gatewayDirectory 网关资源目录
     * @param expected         期望进程数
     * @return 在超时前到达期望值返回 {@code true}
     * @throws IOException          命令执行失败时抛出
     * @throws InterruptedException 等待被中断时抛出
     */
    private static boolean awaitProcessCount(java.nio.file.Path gatewayDirectory, int expected)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline) {
            if (countProcesses(gatewayDirectory) == expected) {
                return true;
            }
            Thread.sleep(200L);
        }
        return false;
    }

    /**
     * 读完一个输入流。
     *
     * @param stream 输入流
     * @return 字节内容
     * @throws IOException 读取失败时抛出
     */
    private static byte[] readAll(InputStream stream) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = stream.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /** 供 {@code statusCommand} 断言用的示例脚本。 */
    private static final String TOOL_SCRIPT = ""
            + "from jellyfish_sdk import tool, command, contributes, ScriptError\n"
            + "\n"
            + "@tool(name=\"jira_issue\", description=\"读 issue\", read_only=True)\n"
            + "def jira_issue(args, ctx):\n"
            + "    if not args.get(\"key\"):\n"
            + "        raise ScriptError(\"缺少参数 key\")\n"
            + "    return \"issue %s 处于 OPEN（会话 %s）\" % (args[\"key\"], ctx.session_id)\n"
            + "\n"
            + "@tool(name=\"jira_hang\", description=\"卡死\")\n"
            + "def jira_hang(args, ctx):\n"
            + "    import time\n"
            + "    time.sleep(600)\n"
            + "    return \"never\"\n"
            + "\n"
            + "@command(\"jira\", summary=\"操作 Jira\")\n"
            + "def jira(tokens, raw, ctx):\n"
            + "    return \"已切换 PROJ-1\"\n"
            + "\n"
            + "@contributes(\"prompt\")\n"
            + "def prompt(ctx):\n"
            + "    return \"Jira：会话 %s 有 3 个未读\" % ctx.session_id\n";

    /**
     * 事件脚本：一个工具负责发布事件，一个处理器负责记录收到的事件。
     * <p>
     * 记录到文件而不是返回给宿主，是因为事件的送达是**异步且旁路**的：
     * 它不会、也不该出现在任何一次调用的返回值里。文件是唯一能从外部观察它的地方。
     */
    private static final String EVENT_SCRIPT = ""
            + "import os\n"
            + "from jellyfish_sdk import tool, subscribe\n"
            + "\n"
            + "LOG = os.path.join(os.path.dirname(os.path.abspath(__file__)), \"events.log\")\n"
            + "\n"
            + "\n"
            + "def record(line):\n"
            + "    with open(LOG, \"a\") as handle:\n"
            + "        handle.write(line + \"\\n\")\n"
            + "\n"
            + "\n"
            + "@tool(name=\"publish\", description=\"发布一条通知\")\n"
            + "def publish(args, ctx):\n"
            + "    ctx.emit_event(\"PluginNotificationEvent\", {\"payload\": {\"hello\": \"world\"}})\n"
            + "    return \"published\"\n"
            + "\n"
            + "\n"
            + "@tool(name=\"publish_forbidden\", description=\"发布一条不可发布的事件\")\n"
            + "def publish_forbidden(args, ctx):\n"
            + "    ctx.emit_event(\"SessionCreatedEvent\", {\"agentId\": \"fake\"})\n"
            + "    return \"attempted\"\n"
            + "\n"
            + "\n"
            + "@subscribe(\"ConfigWarningEvent\")\n"
            + "def on_warning(event, ctx):\n"
            + "    record(\"warning:%s:%s\" % (event.get(\"source\"), event.get(\"message\")))\n"
            + "\n"
            + "\n"
            + "@subscribe(\"PluginNotificationEvent\")\n"
            + "def on_notification(event, ctx):\n"
            + "    record(\"echo:\" + str(event.get(\"payload\")))\n";

    /**
     * 与 {@link #EVENT_SCRIPT} 逐字对应的清单。
     */
    private static final String EVENT_MANIFEST = "{\"entry\":\"main.py\","
            + "\"tools\":[{\"name\":\"publish\"},{\"name\":\"publish_forbidden\"}],"
            + "\"events\":[\"ConfigWarningEvent\",\"PluginNotificationEvent\"]}";

    /**
     * 与 {@link #TOOL_SCRIPT} 逐字对应的清单。
     * <p>
     * 两者必须一起改：这正是「清单与实现必须一致」这条约束在测试里的样子——
     * 用例一旦用一份过时的清单，脚本会直接拒绝服务，而错误信息会立刻指出差在哪一项。
     */
    private static final String FULL_MANIFEST = "{\"entry\":\"main.py\","
            + "\"tools\":[{\"name\":\"jira_issue\",\"readOnly\":true},{\"name\":\"jira_hang\"}],"
            + "\"commands\":[{\"name\":\"jira\",\"descriptor\":{\"summary\":\"操作 Jira\"}}],"
            + "\"contributions\":[\"prompt\"]}";


    /**
     * 第二个脚本：用于验证「一个脚本熔断不牵连同语言的其它脚本」。
     * <p>
     * 它与 {@link #TOOL_SCRIPT} 分属不同目录、各自一个 worker，但在内核眼里提供的是同一批扩展点。
     */
    private static final String GIT_SCRIPT = ""
            + "from jellyfish_sdk import tool\n"
            + "\n"
            + "@tool(name=\"git_status\", description=\"工作区状态\", read_only=True)\n"
            + "def git_status(args, ctx):\n"
            + "    return \"main 干净\"\n";

    /** 与 {@link #GIT_SCRIPT} 逐字对应的清单。 */
    private static final String GIT_MANIFEST = "{\"entry\":\"main.py\","
            + "\"tools\":[{\"name\":\"git_status\",\"readOnly\":true}]}";

    /**
     * 启动插件并完成注册。
     *
     * @param invokeTimeoutSeconds 单次调用超时秒数
     * @throws IOException 安装插件失败时抛出
     */
    private void startRuntime(int invokeTimeoutSeconds) throws IOException {
        installPlugin();
        Map<String, Object> python = new LinkedHashMap<String, Object>();
        python.put(PythonConfig.KEY_SCRIPTS_ROOT, scriptsRoot.toString());
        python.put(PythonConfig.KEY_GATEWAY_ROOT, gatewayRoot.toString());
        python.put(PythonConfig.KEY_INVOKE_TIMEOUT, Integer.valueOf(invokeTimeoutSeconds));
        python.put(PythonConfig.KEY_PYTHON_PATH, interpreter());
        python.put(PythonConfig.KEY_PID_DIRECTORY, pidRoot.toString());
        Map<String, Map<String, Object>> configurations = new LinkedHashMap<String, Map<String, Object>>();
        configurations.put("jellyfish-plugin-python", python);
        manager = new PF4JPluginManager(new PluginContextFactory(extensions, events, registry),
                new PluginRuntimeConfig(Collections.singletonList(pluginsRoot), null, null, configurations));
        manager.bootstrap();
        assertEquals(PluginState.STARTED, manager.stateOf("jellyfish-plugin-python"));
    }

    /**
     * 用默认超时启动插件。
     *
     * @throws IOException 安装插件失败时抛出
     */
    private void startRuntime() throws IOException {
        startRuntime(5);
    }

    /**
     * 调用一个工具。
     *
     * @param toolName  工具名
     * @param arguments 参数
     * @return 调用结果
     */
    private ToolCallResult invokeTool(String toolName, Map<String, Object> arguments) {
        return extensions.invoke(extensions.handler(ToolCallRequest.class, toolName),
                new ToolCallRequest(toolName, arguments, "s-1"));
    }

    /**
     * 反复调用直到成功，用于容忍「worker 正在被替换」的短暂窗口。
     *
     * @param toolName  工具名
     * @param arguments 参数
     * @param timeoutMs 总等待上限
     * @return 最后一次成功的结果
     */
    private ToolCallResult invokeUntilSucceeds(String toolName, Map<String, Object> arguments,
                                               long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        JellyfishException last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                return invokeTool(toolName, arguments);
            } catch (JellyfishException e) {
                last = e;
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new AssertionError("重试窗口内始终未恢复", last);
    }

    /**
     * 在脚本根目录下写一个脚本。
     *
     * @param id       脚本标识
     * @param source   入口源码
     * @param manifest 清单正文
     * @throws IOException 写入失败时抛出
     */
    private void writeScript(String id, String source, String manifest) throws IOException {
        Path directory = scriptsRoot.resolve(id);
        Files.createDirectories(directory);
        Files.write(directory.resolve("main.py"), source.getBytes(StandardCharsets.UTF_8));
        Files.write(directory.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 把真实的 {@code plugin.properties} 装进临时插件根目录，形成 PF4J 认识的独立插件目录。
     *
     * @throws IOException 复制失败时抛出
     */
    private void installPlugin() throws IOException {
        // 与 PythonPluginLoadingTest 同一套装载方式：PF4J 认「插件目录 + plugin.properties」，
        // 目录名必须是 plugin.id——这也是 PF4J 判定插件身份的依据
        Path directory = pluginsRoot.resolve("jellyfish-plugin-python");
        Files.createDirectories(directory);
        try (InputStream stream = PythonScriptIT.class.getResourceAsStream("/plugin.properties")) {
            assertTrue(stream != null, "测试类路径上找不到 plugin.properties");
            Files.copy(stream, directory.resolve("plugin.properties"));
        }
    }

    /**
     * 判断本机是否有可用的解释器。
     *
     * @return 可用返回 {@code true}
     */
    private static boolean interpreterAvailable() {
        try {
            Process process = new ProcessBuilder(interpreter(), "--version")
                    .redirectErrorStream(true).start();
            boolean finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int code = finished ? process.exitValue() : -1;
            process.destroyForcibly();
            return finished && code == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 取解释器路径，允许用系统属性覆盖（例如指向虚拟环境里的解释器）。
     *
     * @return 解释器路径或命令名
     */
    private static String interpreter() {
        return System.getProperty("jellyfish.test.python", PythonConfig.DEFAULT_PYTHON_PATH);
    }

}
