package zcd.jellyfish.cli;

import zcd.jellyfish.cli.console.ConsoleIO;
import zcd.jellyfish.infra.config.AppConfig;
import zcd.jellyfish.infra.config.ConfigPaths;
import zcd.jellyfish.infra.config.ProjectConfigTrust;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 启动期的项目级配置信任确认。
 * <p>
 * <b>它解决的是什么</b>：项目级配置（{@code ./.jellyfish/*.json}）按当前目录读取，因此它的内容取决于
 * 「在哪个仓库里启动」。而它足以改写模型端点与密钥、新增 agent、改落盘目录与插件配置——
 * 于是一个 {@code git clone} 下来的目录就能改变运行行为。默认不加载（见 {@link ProjectConfigTrust}），
 * 本类负责在那之前把「要不要加载」这件事问清楚。
 * <p>
 * <b>为什么必须在 {@code bootstrap()} 之前</b>：配置在 {@code AgentHarness.bootstrap()} 里经
 * {@code RuntimeConfig.refresh()} 装载，晚一步问就等于问了也不会生效。
 * <p>
 * <b>三种外壳的区别</b>：{@code -tui} 有人在场，问一次并允许记住；{@code -cli} / {@code -server}
 * 无人可问，只打印一行提示说明「怎么才能加载」，绝不擅自加载。
 * <p>
 * <b>缺省答案是不加载</b>：空白输入、无法识别的内容、EOF 一律按「不加载」处理。这类确认的口子
 * 必须往紧的方向缺省——用户按错一个键的代价不该是「信任了一个不可信仓库」。
 * <p>
 * 无状态（读入的依赖都是只读协作者），可安全复用。
 *
 * @author zcd
 */
final class ProjectConfigTrustConsent {

    /** 应答：加载并记住。 */
    private static final String ANSWER_REMEMBER = "y";

    /** 应答：仅本次加载。 */
    private static final String ANSWER_ONCE = "o";

    /** 输出面板。 */
    private final ConsoleIO console;

    /** 信任裁决。 */
    private final ProjectConfigTrust trust;

    /**
     * 构造确认器。
     *
     * @param console 输出面板，不可为 {@code null}
     * @param trust   信任裁决，不可为 {@code null}
     */
    ProjectConfigTrustConsent(ConsoleIO console, ProjectConfigTrust trust) {
        this.console = Objects.requireNonNull(console, "console must not be null");
        this.trust = Objects.requireNonNull(trust, "trust must not be null");
    }

    /**
     * 在启动内核之前定下项目级配置的信任状态。
     * <p>
     * {@code --trust-project-config} 优先：用户既然已经在命令行上表了态，就不该再被问一遍，
     * 也不该因为答不上来而拿不到自己刚要的东西。
     *
     * @param appConfig   应用级配置，提供各配置段的双源路径，可为 {@code null}
     * @param options     启动参数，不可为 {@code null}
     * @param interactive 当前外壳是否能与用户交互（{@code -tui} 为 {@code true}）
     */
    void resolve(AppConfig appConfig, StartupOptions options, boolean interactive) {
        if (options.isTrustProjectConfig()) {
            trust.trustEverythingInThisRun();
            return;
        }
        List<String> pending = pendingProjectConfigs(appConfig);
        if (pending.isEmpty()) {
            return;
        }
        if (interactive) {
            ask(pending);
            return;
        }
        // 无人可问：只说明情况与出路，不擅自加载
        for (String path : pending) {
            console.writeErrLine("提示：项目级配置未加载（未被信任）：" + path);
        }
        console.writeErrLine("  项目级配置能改写模型端点与密钥、新增 agent、改落盘目录，因此默认不加载。"
                + "确认该目录可信后，加 --trust-project-config 重新启动即可加载。");
    }

    /**
     * 列出存在但尚未被信任的项目级配置文件。
     *
     * @param appConfig 应用级配置，可为 {@code null}
     * @return 待确认的路径列表，保证非 {@code null}
     */
    private List<String> pendingProjectConfigs(AppConfig appConfig) {
        Set<String> pending = new LinkedHashSet<String>();
        if (appConfig == null) {
            return new ArrayList<String>(pending);
        }
        // 三段各有一份项目级文件；用 LinkedHashSet 既去重（三段可以配成同一个文件）又保留声明顺序
        List<ConfigPaths> sections = new ArrayList<ConfigPaths>();
        sections.add(appConfig.getModel());
        sections.add(appConfig.getAgent());
        sections.add(appConfig.getJellyfish());
        for (ConfigPaths paths : sections) {
            if (paths == null) {
                continue;
            }
            String projectPath = paths.getProjectPath();
            if (trust.decide(projectPath) == ProjectConfigTrust.Decision.UNTRUSTED) {
                pending.add(projectPath);
            }
        }
        return new ArrayList<String>(pending);
    }

    /**
     * 就地问一次，并按应答授予信任。
     *
     * @param pending 待确认的路径列表，不可为 {@code null}
     */
    private void ask(List<String> pending) {
        console.writeErrLine("发现 " + pending.size() + " 份项目级配置（未被信任）：");
        for (String path : pending) {
            console.writeErrLine("  " + path);
        }
        console.writeErrLine("它们能改写模型端点与密钥、新增 agent、改落盘目录，因此默认不加载。");
        console.writeErr("加载吗？[y] 加载并记住（写入 " + trust.storeFile() + "）/ [o] 仅本次加载 / 其他 = 不加载：");
        String answer = console.readLine();
        String normalized = answer == null ? "" : answer.trim().toLowerCase(Locale.ROOT);
        if (ANSWER_REMEMBER.equals(normalized)) {
            for (String path : pending) {
                if (!trust.grant(path)) {
                    // 写不进仓库不该让这次启动失败：本进程内仍然按已信任处理，只是下次还要再问
                    trust.trustForSession(path);
                    console.writeErrLine("提示：信任记录写入失败，本次仍会加载：" + path);
                }
            }
            return;
        }
        if (ANSWER_ONCE.equals(normalized)) {
            for (String path : pending) {
                trust.trustForSession(path);
            }
            return;
        }
        for (String path : pending) {
            console.writeErrLine("已跳过项目级配置：" + path);
        }
    }
}
