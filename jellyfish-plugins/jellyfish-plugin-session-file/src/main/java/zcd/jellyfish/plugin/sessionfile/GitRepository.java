package zcd.jellyfish.plugin.sessionfile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * git 仓库封装：保证会话目录是一个仓库，并在每次落盘后留一次提交。
 * <p>
 * <b>git 只是版本化层，不是持久化本身</b>：文件写成功就意味着会话没丢，因此这里的失败一律只记告警、
 * 不上抛。反过来做（git 出错就让对话失败）会让「机器上没装 git」「仓库权限不对」这类环境问题
 * 直接升级成「jellyfish 不能说话」，代价与收益完全不成比例。
 * <p>
 * <b>提交身份用 {@code -c} 临时指定</b>：新机器上没有任何 {@code user.name} / {@code user.email}
 * 配置时 {@code git commit} 会直接失败。用命令行覆盖只影响本插件发起的提交，不会动用户全局配置，
 * 也不会污染用户自己仓库的提交身份。
 * <p>
 * <b>只认 {@code <会话目录>/.git}</b>：仓库要么已经存在，要么由本插件 {@code git init} 出来，
 * 绝不向上寻找父仓库——那样会把用户的仓库当成本插件的存储，用户仓库里凭空出现会话提交是无法接受的。
 *
 * @author zcd
 */
final class GitRepository {

    /** 日志。 */
    private static final Logger LOG = LoggerFactory.getLogger(GitRepository.class);

    /** 提交者名，仅用于本插件发起的提交。 */
    private static final String COMMITTER_NAME = "jellyfish";

    /** 提交者邮箱，仅用于本插件发起的提交。 */
    private static final String COMMITTER_EMAIL = "jellyfish@localhost";

    /** 单条 git 命令的超时秒数：git 挂住不该把对话一起挂住。 */
    private static final long TIMEOUT_SECONDS = 30L;

    /** 等待读取线程收尾的毫秒数。 */
    private static final long JOIN_MILLIS = 1000L;

    /** 仓库目录。 */
    private final Path directory;

    /** 是否启用 git（来自插件配置）。 */
    private final boolean enabled;

    /** 是否已经确认过仓库可用；{@code false} 表示还需要探测或初始化。 */
    private boolean ready;

    /** 是否已经因环境问题放弃 git，避免每次落盘都重试一遍。 */
    private boolean abandoned;

    /**
     * 构造 git 封装。
     *
     * @param directory 仓库目录（即会话目录），不可为 {@code null}
     * @param enabled   配置是否启用 git
     */
    GitRepository(Path directory, boolean enabled) {
        this.directory = directory;
        this.enabled = enabled;
    }

    /**
     * 为一次落盘留一次提交。
     * <p>
     * 同步执行，失败只记告警。内容没有变化时 git 会以「nothing to commit」失败，这属于正常情况，
     * 不当错误处理。
     *
     * @param file    已落盘的文件
     * @param message 提交信息
     */
    synchronized void commit(Path file, String message) {
        if (!enabled || abandoned) {
            return;
        }
        if (!ensureRepository()) {
            return;
        }
        String fileName = file.getFileName().toString();
        CommandResult add = run("add", "--", fileName);
        if (add.exitCode != 0) {
            LOG.warn("git add 失败，本次不提交: file={} output={}", fileName, add.output.trim());
            return;
        }
        CommandResult commit = run("commit", "-m", message);
        if (commit.exitCode != 0 && !commit.output.contains("nothing to commit")) {
            LOG.warn("git commit 失败: file={} output={}", fileName, commit.output.trim());
        }
    }

    /**
     * 确保目录是一个仓库。
     *
     * @return 可用返回 {@code true}；环境不具备时返回 {@code false} 并永久放弃 git
     */
    private boolean ensureRepository() {
        if (ready) {
            return true;
        }
        if (!isGitAvailable()) {
            LOG.warn("未找到可用的 git，会话仍会落盘，但不再提交版本历史: dir={}", directory);
            abandoned = true;
            return false;
        }
        if (!Files.isDirectory(directory.resolve(".git"))) {
            CommandResult init = run("init");
            if (init.exitCode != 0) {
                LOG.warn("git init 失败，会话仍会落盘，但不再提交版本历史: dir={} output={}",
                        directory, init.output.trim());
                abandoned = true;
                return false;
            }
            LOG.info("已在会话目录初始化 git 仓库: dir={}", directory);
        }
        ready = true;
        return true;
    }

    /**
     * 探测 git 是否可用。
     *
     * @return 可用返回 {@code true}
     */
    private boolean isGitAvailable() {
        return run("--version").exitCode == 0;
    }

    /**
     * 执行一条 git 命令。
     * <p>
     * 命令不存在、超时、被中断都归为「执行失败」并返回非零退出码，调用方据此决定告警还是放弃。
     *
     * @param args 子命令与参数
     * @return 退出码与合并输出
     */
    private CommandResult run(String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-c");
        command.add("user.name=" + COMMITTER_NAME);
        command.add("-c");
        command.add("user.email=" + COMMITTER_EMAIL);
        command.addAll(Arrays.asList(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            OutputCollector collector = new OutputCollector(process.getInputStream());
            // 读取必须与等待并发：输出写满管道缓冲区时进程会阻塞在写上，等结束会导致双方互等
            Thread reader = new Thread(collector, "jellyfish-git-output");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                reader.join(JOIN_MILLIS);
                return new CommandResult(-1, collector.text() + "\n[超时]");
            }
            reader.join(JOIN_MILLIS);
            return new CommandResult(process.exitValue(), collector.text());
        } catch (IOException e) {
            return new CommandResult(-1, e.getMessage() == null ? e.toString() : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(-1, "被中断");
        }
    }

    /**
     * 读取进程输出的守护任务：把 stdout 读完，供主线程在等待之余随时取值。
     *
     * @author zcd
     */
    private static final class OutputCollector implements Runnable {

        /** 进程输出流。 */
        private final InputStream input;

        /** 输出缓冲区。 */
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        /**
         * 构造收集器。
         *
         * @param input 进程输出流，不可为 {@code null}
         */
        private OutputCollector(InputStream input) {
            this.input = input;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[4096];
            try (InputStream stream = input) {
                int read;
                while ((read = stream.read(chunk)) >= 0) {
                    synchronized (buffer) {
                        buffer.write(chunk, 0, read);
                    }
                }
            } catch (IOException e) {
                // 进程被杀掉时读侧报错是预期内的，没有可恢复动作
            }
        }

        /**
         * 取当前已读到的输出。
         *
         * @return 输出文本
         */
        private String text() {
            synchronized (buffer) {
                return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }

    /**
     * 一条 git 命令的执行结果。
     */
    private static final class CommandResult {

        /** 退出码，非零表示失败，{@code -1} 表示命令没能执行。 */
        private final int exitCode;

        /** 合并后的标准输出与标准错误。 */
        private final String output;

        /**
         * 构造执行结果。
         *
         * @param exitCode 退出码
         * @param output   合并输出
         */
        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }
}
