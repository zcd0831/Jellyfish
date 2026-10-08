package zcd.jellyfish.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import zcd.jellyfish.cli.console.RecordingConsoleIO;
import zcd.jellyfish.infra.config.AppConfig;
import zcd.jellyfish.infra.config.ConfigPaths;
import zcd.jellyfish.infra.config.ProjectConfigTrust;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProjectConfigTrustConsent} 的单元测试。
 * <p>
 * 最要紧的一条是<b>缺省不加载</b>：空白输入、无法识别的输入、EOF 都必须落到「不加载」。
 * 这类确认框出现在「打开一个不可信仓库」的现场，默认值答错一个键的代价就是把运行行为交给那个仓库。
 *
 * @author zcd
 */
@DisplayName("项目级配置信任确认")
class ProjectConfigTrustConsentTest {

    /** 临时目录：项目级配置与信任仓库都放这里。 */
    @TempDir
    Path tempDir;

    @Test
    @DisplayName("带 --trust-project-config 时直接信任，不再追问")
    void resolve_should_trustEverything_when_flagGiven() throws IOException {
        RecordingConsoleIO console = new RecordingConsoleIO("");
        ProjectConfigTrust trust = trust();
        StartupOptions options = StartupOptions.builder(StartupOptions.Mode.TUI)
                .trustProjectConfig(true).build();

        new ProjectConfigTrustConsent(console, trust)
                .resolve(appConfigWith(configFile("jellyfish.json")), options, true);

        assertTrue(trust.isTrustingEverythingInThisRun());
        assertEquals("", console.err(), "命令行已经表过态，不该再弹问题");
    }

    @Test
    @DisplayName("无人可问时不加载，并说明怎么加载")
    void resolve_should_notLoad_when_notInteractive() throws IOException {
        String path = configFile("jellyfish.json");
        RecordingConsoleIO console = new RecordingConsoleIO("");
        ProjectConfigTrust trust = trust();

        new ProjectConfigTrustConsent(console, trust)
                .resolve(appConfigWith(path), options(false), false);

        assertFalse(trust.isTrustingEverythingInThisRun());
        assertEquals(ProjectConfigTrust.Decision.UNTRUSTED, trust.decide(path));
        // 只说「没加载」而不说怎么加载，用户面前就是一个没有出口的死角
        assertTrue(console.err().contains("--trust-project-config"), console.err());
    }

    @Test
    @DisplayName("回答 y 时加载并记住，下次进程直接生效")
    void resolve_should_grantAndPersist_when_userAnswersYes() throws IOException {
        String path = configFile("jellyfish.json");
        Path store = tempDir.resolve("trust.json");
        ProjectConfigTrust trust = new ProjectConfigTrust(store);
        RecordingConsoleIO console = new RecordingConsoleIO("").withLine("y");

        new ProjectConfigTrustConsent(console, trust).resolve(appConfigWith(path), options(false), true);

        assertEquals(ProjectConfigTrust.Decision.LOAD, trust.decide(path));
        assertTrue(console.err().contains("发现 1 份项目级配置"), console.err());
        assertEquals(ProjectConfigTrust.Decision.LOAD,
                new ProjectConfigTrust(store).decide(path), "「记住」应当真的落盘");
    }

    @Test
    @DisplayName("回答 o 时只对本次进程生效，不落盘")
    void resolve_should_grantForSessionOnly_when_userAnswersOnce() throws IOException {
        String path = configFile("jellyfish.json");
        Path store = tempDir.resolve("trust.json");
        ProjectConfigTrust trust = new ProjectConfigTrust(store);
        RecordingConsoleIO console = new RecordingConsoleIO("").withLine("o");

        new ProjectConfigTrustConsent(console, trust).resolve(appConfigWith(path), options(false), true);

        assertEquals(ProjectConfigTrust.Decision.LOAD, trust.decide(path));
        assertFalse(Files.exists(store), "「仅本次」不该写信任仓库");
        assertEquals(ProjectConfigTrust.Decision.UNTRUSTED, new ProjectConfigTrust(store).decide(path));
    }

    @Test
    @DisplayName("空白输入按不加载处理——缺省值必须往紧的方向倒")
    void resolve_should_skip_when_userAnswersBlank() throws IOException {
        String path = configFile("jellyfish.json");
        RecordingConsoleIO console = new RecordingConsoleIO("").withLine("");

        ProjectConfigTrust trust = trust();
        new ProjectConfigTrustConsent(console, trust).resolve(appConfigWith(path), options(false), true);

        assertEquals(ProjectConfigTrust.Decision.UNTRUSTED, trust.decide(path));
        assertTrue(console.err().contains("已跳过"), console.err());
    }

    @Test
    @DisplayName("输入到 EOF（拿不到应答）同样按不加载处理")
    void resolve_should_skip_when_answerIsMissing() throws IOException {
        String path = configFile("jellyfish.json");
        RecordingConsoleIO console = new RecordingConsoleIO("");

        ProjectConfigTrust trust = trust();
        new ProjectConfigTrustConsent(console, trust).resolve(appConfigWith(path), options(false), true);

        assertEquals(ProjectConfigTrust.Decision.UNTRUSTED, trust.decide(path));
    }

    @Test
    @DisplayName("无法识别的输入按不加载处理")
    void resolve_should_skip_when_answerUnrecognized() throws IOException {
        String path = configFile("jellyfish.json");
        RecordingConsoleIO console = new RecordingConsoleIO("").withLine("yes please");

        ProjectConfigTrust trust = trust();
        new ProjectConfigTrustConsent(console, trust).resolve(appConfigWith(path), options(false), true);

        assertEquals(ProjectConfigTrust.Decision.UNTRUSTED, trust.decide(path));
    }

    @Test
    @DisplayName("没有项目级配置文件时静默通过，不打扰用户")
    void resolve_should_doNothing_when_noProjectConfigExists() {
        RecordingConsoleIO console = new RecordingConsoleIO("");
        ProjectConfigTrust trust = trust();

        new ProjectConfigTrustConsent(console, trust)
                .resolve(appConfigWith(tempDir.resolve("missing.json").toString()), options(false), true);

        assertEquals("", console.err());
        assertFalse(trust.isTrustingEverythingInThisRun());
    }

    @Test
    @DisplayName("信任过、且内容没变的文件不再打扰")
    void resolve_should_doNothing_when_alreadyTrusted() throws IOException {
        String path = configFile("jellyfish.json");
        ProjectConfigTrust trust = trust();
        trust.grant(path);
        RecordingConsoleIO console = new RecordingConsoleIO("");

        new ProjectConfigTrustConsent(console, trust).resolve(appConfigWith(path), options(false), true);

        assertEquals("", console.err());
    }

    /**
     * 构造指向临时目录信任仓库的裁决。
     *
     * @return 信任裁决
     */
    private ProjectConfigTrust trust() {
        return new ProjectConfigTrust(tempDir.resolve("trust.json"));
    }

    /**
     * 构造只带一个项目级路径的应用级配置。
     *
     * @param projectPath 项目级路径
     * @return 应用级配置
     */
    private static AppConfig appConfigWith(String projectPath) {
        ConfigPaths paths = new ConfigPaths();
        paths.setProjectPath(projectPath);
        return new AppConfig(null, paths, new ConfigPaths(), new ConfigPaths(), null);
    }

    /**
     * 构造不带信任旗标的启动参数。
     *
     * @param trustProjectConfig 是否信任项目级配置
     * @return 启动参数
     */
    private static StartupOptions options(boolean trustProjectConfig) {
        return StartupOptions.builder(StartupOptions.Mode.CLI)
                .trustProjectConfig(trustProjectConfig).build();
    }

    /**
     * 在临时目录写一个项目级配置文件。
     *
     * @param name 文件名
     * @return 文件路径（字符串形式，与配置里声明的形式一致）
     * @throws IOException 写文件失败
     */
    private String configFile(String name) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        return file.toString();
    }
}
