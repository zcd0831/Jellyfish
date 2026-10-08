package zcd.jellyfish.infra.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProjectConfigTrust} 的单元测试。
 * <p>
 * 钉住的是「默认不信任」与「内容一变就失效」这两条：它们分别是这道闸能不能挡住
 * 「clone 一个仓库就提权」与「pull 一次就悄悄放宽」的关键。信任仓库的读写也一并覆盖，
 * 否则「记下来了」这件事无法与「写盘成功」区分。
 *
 * @author zcd
 */
@DisplayName("项目级配置信任裁决")
class ProjectConfigTrustTest {

    /** 临时目录：信任仓库与项目级配置都放这里。 */
    @TempDir
    Path tempDir;

    @Test
    @DisplayName("文件不存在时返回 ABSENT（正常情形，不该告警）")
    void decide_should_returnAbsent_when_fileMissing() {
        ProjectConfigTrust trust = trust();

        assertEquals(ProjectConfigTrust.Decision.ABSENT, trust.decide(tempDir.resolve("nope.json").toString()));
        assertEquals(ProjectConfigTrust.Decision.ABSENT, trust.decide(null));
        assertEquals(ProjectConfigTrust.Decision.ABSENT, trust.decide("   "));
    }

    @Test
    @DisplayName("文件存在但没被授予信任时返回 UNTRUSTED")
    void decide_should_returnUntrusted_when_notGranted() throws IOException {
        Path config = configFile("jellyfish.json", "{\"a\":1}");

        assertEquals(ProjectConfigTrust.Decision.UNTRUSTED, trust().decide(config.toString()));
    }

    @Test
    @DisplayName("授予信任后按内容指纹放行")
    void decide_should_returnLoad_when_granted() throws IOException {
        Path config = configFile("jellyfish.json", "{\"a\":1}");
        ProjectConfigTrust trust = trust();

        assertTrue(trust.grant(config.toString()));

        assertEquals(ProjectConfigTrust.Decision.LOAD, trust.decide(config.toString()));
    }

    @Test
    @DisplayName("信任记录落盘，换一个实例仍然生效")
    void decide_should_returnLoad_when_recordPersisted() throws IOException {
        Path config = configFile("jellyfish.json", "{\"a\":1}");
        Path store = tempDir.resolve("trust.json");
        assertTrue(new ProjectConfigTrust(store).grant(config.toString()));

        // 新实例从盘上读回记录：否则「确认过一次」在下次启动就失效了
        assertEquals(ProjectConfigTrust.Decision.LOAD,
                new ProjectConfigTrust(store).decide(config.toString()));
    }

    @Test
    @DisplayName("内容变过即作废——一次 git pull 不该沿用旧的信任")
    void decide_should_returnUntrusted_when_contentChanged() throws IOException {
        Path config = configFile("jellyfish.json", "{\"a\":1}");
        ProjectConfigTrust trust = trust();
        trust.grant(config.toString());

        Files.write(config, "{\"a\":2}".getBytes(StandardCharsets.UTF_8));

        assertEquals(ProjectConfigTrust.Decision.UNTRUSTED, trust.decide(config.toString()));
    }

    @Test
    @DisplayName("classpath 形式的项目级配置不受闸门管辖——它属于构件本身")
    void decide_should_returnLoad_when_declaredPathIsClasspathResource() {
        ProjectConfigTrust trust = trust();

        assertEquals(ProjectConfigTrust.Decision.LOAD, trust.decide("classpath:jellyfish/models.json"));
        // 前缀带空白也要认出来，否则一个回车就会让配置静默不加载
        assertEquals(ProjectConfigTrust.Decision.LOAD, trust.decide("  classpath:jellyfish/agents.json"));
    }

    @Test
    @DisplayName("仅本次授予的信任不落盘")
    void trustForSession_should_notPersist() throws IOException {
        Path config = configFile("jellyfish.json", "{\"a\":1}");
        Path store = tempDir.resolve("trust.json");
        ProjectConfigTrust trust = new ProjectConfigTrust(store);
        trust.trustForSession(config.toString());

        assertEquals(ProjectConfigTrust.Decision.LOAD, trust.decide(config.toString()));
        assertFalse(Files.exists(store), "「仅本次」不该写信任仓库");
        assertEquals(ProjectConfigTrust.Decision.UNTRUSTED,
                new ProjectConfigTrust(store).decide(config.toString()));
    }

    @Test
    @DisplayName("信任全部时不再看单个文件的指纹")
    void decide_should_returnLoad_when_trustingEverything() throws IOException {
        Path config = configFile("jellyfish.json", "{\"a\":1}");
        ProjectConfigTrust trust = trust();
        trust.trustEverythingInThisRun();

        assertEquals(ProjectConfigTrust.Decision.LOAD, trust.decide(config.toString()));
        assertTrue(trust.isTrustingEverythingInThisRun());
    }

    @Test
    @DisplayName("对不存在的文件授予信任应失败，且不污染信任仓库")
    void grant_should_returnFalse_when_fileMissing() {
        Path store = tempDir.resolve("trust.json");
        ProjectConfigTrust trust = new ProjectConfigTrust(store);

        assertFalse(trust.grant(tempDir.resolve("nope.json").toString()));
        assertFalse(trust.grant(null));
        assertFalse(Files.exists(store));
    }

    @Test
    @DisplayName("信任仓库内容非法时按「一份都不信任」处理，闸门只会更紧")
    void decide_should_returnUntrusted_when_storeCorrupted() throws IOException {
        Path config = configFile("jellyfish.json", "{\"a\":1}");
        Path store = tempDir.resolve("trust.json");
        assertTrue(new ProjectConfigTrust(store).grant(config.toString()));
        Files.write(store, "这不是 JSON".getBytes(StandardCharsets.UTF_8));

        assertEquals(ProjectConfigTrust.Decision.UNTRUSTED,
                new ProjectConfigTrust(store).decide(config.toString()));
    }

    /**
     * 构造指向临时目录的信任仓库。
     *
     * @return 信任裁决
     */
    private ProjectConfigTrust trust() {
        return new ProjectConfigTrust(tempDir.resolve("trust.json"));
    }

    /**
     * 在临时目录写一个项目级配置文件。
     *
     * @param name    文件名
     * @param content 内容
     * @return 文件路径
     * @throws IOException 写文件失败
     */
    private Path configFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
