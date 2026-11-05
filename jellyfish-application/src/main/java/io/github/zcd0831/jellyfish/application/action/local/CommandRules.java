package io.github.zcd0831.jellyfish.application.action.local;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * `run_command` 的绊线：默认放行，命中毒性特征才停下来问人。
 * 它是**绊线不是闸门**——拦不住动态拼接、间接执行与项目自己的脚本。
 * 规则出处：05-action §5.8.1 规则 10。
 */
public final class CommandRules {

    /** 外层之外最多再看两层 `sh -c`：再深就不是"看一眼能判断"的命令了。 */
    private static final int MAX_DEPTH = 2;
    private static final List<String> SHELLS = Arrays.asList("sh", "bash", "zsh", "dash", "ksh");
    private static final List<String> DOWNLOADERS = Arrays.asList("curl", "wget", "base64", "eval", "nc", "ncat");
    private static final List<String> DESTRUCTIVE = Arrays.asList("rm", "mv", "dd", "shred", "truncate", "srm");
    private static final java.util.regex.Pattern DESTRUCTIVE_SQL = java.util.regex.Pattern.compile(
            "(?i)\\b(drop\\s+(database|table)|truncate\\s+table)\\b");
    private static final List<String> CRITICAL_DIRS =
            Arrays.asList("/", "/*", "~", "~/", "$HOME", "$HOME/", "/etc", "/usr", "/var", "/Users");

    private CommandRules() {
    }

    /** 三态：放行 / 需审批（带命中的规则名）/ 无法解析。 */
    public static final class Verdict {

        private final boolean allowed;
        private final String reason;

        private Verdict(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        static Verdict allow() {
            return new Verdict(true, null);
        }

        static Verdict ask(String reason) {
            return new Verdict(false, reason);
        }

        public boolean allowed() {
            return allowed;
        }

        public String reason() {
            return reason;
        }
    }

    public static Verdict assess(String commandLine) {
        return assess(commandLine, 0);
    }

    private static Verdict assess(String commandLine, int depth) {
        CommandParsing.Result parsed = CommandParsing.parse(commandLine);
        if (!parsed.parsed()) {
            return Verdict.ask("无法安全解析这条命令：" + parsed.reason());
        }
        List<CommandParsing.Segment> segments = parsed.segments();
        if (segments.isEmpty()) {
            return Verdict.allow();
        }
        Verdict forkBomb = checkForkBomb(parsed);
        if (!forkBomb.allowed()) {
            return forkBomb;
        }
        Verdict verdict = check(segments);
        if (!verdict.allowed()) {
            return verdict;
        }
        return checkNested(segments, depth);
    }

    private static Verdict check(List<CommandParsing.Segment> segments) {
        for (int i = 0; i < segments.size(); i++) {
            CommandParsing.Segment segment = segments.get(i);
            CommandParsing.Segment upstream = i > 0 ? segments.get(i - 1) : null;
            Verdict verdict = checkSegment(segment, upstream);
            if (!verdict.allowed()) {
                return verdict;
            }
        }
        return Verdict.allow();
    }

    /**
     * 一步一条规则，按顺序问；第一个"要问人"的赢。拆成表而不是一长串 if：
     * 加一条规则只加一个条目，也不用再数圈复杂度（05-action §5.8.1 规则 10）。
     */
    private interface SegmentCheck {
        Verdict check(CommandParsing.Segment segment, CommandParsing.Segment upstream);
    }

    private static final List<SegmentCheck> SEGMENT_CHECKS = Collections.unmodifiableList(Arrays.asList(
            CommandRules::checkDangerousProgram,
            CommandRules::checkEscalation,
            CommandRules::checkDeviceWrites,
            CommandRules::checkRecursiveOnCriticalDir,
            CommandRules::checkProcessWide,
            CommandRules::checkGit,
            CommandRules::checkIndirectExecution,
            CommandRules::checkPipedShell,
            CommandRules::checkSql,
            CommandRules::checkRemoteWipe,
            CommandRules::checkSecrets));

    private static Verdict checkSegment(CommandParsing.Segment segment, CommandParsing.Segment upstream) {
        if (segment.program().isEmpty()) {
            return Verdict.allow();
        }
        for (SegmentCheck check : SEGMENT_CHECKS) {
            Verdict verdict = check.check(segment, upstream);
            if (!verdict.allowed()) {
                return verdict;
            }
        }
        return Verdict.allow();
    }

    /** 磁盘工具与关机：一条命令就能抹掉整块设备或整台机器。 */
    private static Verdict checkDangerousProgram(CommandParsing.Segment segment, CommandParsing.Segment ignored) {
        String program = segment.program();
        if (program.startsWith("mkfs") || DANGEROUS_PROGRAMS.contains(program)) {
            return Verdict.ask("危险命令：" + program);
        }
        return Verdict.allow();
    }

    private static Verdict checkEscalation(CommandParsing.Segment segment, CommandParsing.Segment ignored) {
        String program = segment.program();
        if (ESCALATORS.contains(program)) {
            return Verdict.ask("提权执行：" + program);
        }
        return Verdict.allow();
    }

    /** 写设备文件：`dd of=/dev/...` 与重定向到 `/dev/sd*` 都是原地覆写。 */
    private static Verdict checkDeviceWrites(CommandParsing.Segment segment, CommandParsing.Segment ignored) {
        if (segment.is("dd") && anyContains(segment.operands(), "of=/dev/")) {
            return Verdict.ask("原地覆写设备");
        }
        if (anyContains(segment.redirects(), "/dev/sd") || anyContains(segment.redirects(), "/dev/disk")) {
            return Verdict.ask("写入块设备");
        }
        return Verdict.allow();
    }

    /** 递归动系统目录或家目录：`rm -rf /`、`chmod -R 777 /etc`。 */
    private static Verdict checkRecursiveOnCriticalDir(CommandParsing.Segment segment, CommandParsing.Segment ignored) {
        if (segment.is("chmod") && segment.hasFlag("R") && hasCriticalTarget(segment)) {
            return Verdict.ask("递归改动系统目录权限");
        }
        if (segment.is("rm") && recursive(segment) && hasCriticalTarget(segment)) {
            return Verdict.ask("递归删除系统目录或家目录");
        }
        return Verdict.allow();
    }

    private static Verdict checkProcessWide(CommandParsing.Segment segment, CommandParsing.Segment ignored) {
        if (segment.is("kill") && anyContains(segment.operands(), "-1")) {
            return Verdict.ask("杀全部进程");
        }
        return Verdict.allow();
    }

    /** `find -delete/-exec` 与 `xargs rm`：把删除藏进别的命令里。 */
    private static Verdict checkIndirectExecution(CommandParsing.Segment segment, CommandParsing.Segment ignored) {
        if (segment.is("find") && (segment.hasFlag("delete") || segment.hasFlag("exec"))) {
            return Verdict.ask("find 直接删除或执行命令");
        }
        if (segment.is("xargs") && DESTRUCTIVE.contains(segment.firstArgument())) {
            return Verdict.ask("xargs 派生删除类命令");
        }
        return Verdict.allow();
    }

    /** `curl ... | sh`：把下载到的内容直接交给 shell。 */
    private static Verdict checkPipedShell(CommandParsing.Segment segment, CommandParsing.Segment upstream) {
        if (SHELLS.contains(segment.program()) && upstream != null && "|".equals(segment.separator())
                && DOWNLOADERS.contains(upstream.program())) {
            return Verdict.ask("把下载到的内容直接交给 shell 执行");
        }
        return Verdict.allow();
    }

    /** 函数定义 + 后台执行：这是 fork 炸弹的形状，正常命令不会长这样。 */
    private static Verdict checkForkBomb(CommandParsing.Result parsed) {
        if (!parsed.hasFunctionDefinition()) {
            return Verdict.allow();
        }
        for (CommandParsing.Segment segment : parsed.segments()) {
            if ("&".equals(segment.separator())) {
                return Verdict.ask("疑似 fork 炸弹（函数定义 + 后台执行）");
            }
        }
        return Verdict.allow();
    }

    /** 破坏性 SQL 藏在 `mysql -e '...'` / `psql -c "..."` 的实参里，所以看操作数文本。 */
    private static Verdict checkSql(CommandParsing.Segment segment, CommandParsing.Segment ignored) {
        for (String operand : segment.operands()) {
            if (DESTRUCTIVE_SQL.matcher(operand).find()) {
                return Verdict.ask("破坏性 SQL：" + operand);
            }
        }
        return Verdict.allow();
    }

    /** 取值的 git 选项：`-C /repo` 会把子命令顶掉，不能直接用 firstArgument。 */
    private static final List<String> DANGEROUS_PROGRAMS =
            Arrays.asList("shred", "fdisk", "shutdown", "reboot", "halt");

    private static final List<String> ESCALATORS = Arrays.asList("sudo", "su", "doas");

    private static final List<String> GIT_VALUE_OPTIONS = Arrays.asList(
            "-C", "-c", "--git-dir", "--work-tree", "--namespace", "--exec-path");

    private static Verdict checkGit(CommandParsing.Segment segment, CommandParsing.Segment ignored) {
        String sub = gitSubcommand(segment);
        if ("push".equals(sub) && (segment.hasFlag("f") || segment.hasFlag("force"))) {
            return Verdict.ask("强制推送会改写远端历史");
        }
        if ("reset".equals(sub) && segment.hasFlag("hard")) {
            return Verdict.ask("硬重置会丢掉未提交的改动");
        }
        if ("clean".equals(sub) && segment.hasFlag("x")) {
            return Verdict.ask("清理未跟踪文件");
        }
        return Verdict.allow();
    }

    /** 跳过取值选项后，第一个非选项操作数才是子命令（`git -C /repo push --force`）。 */
    private static String gitSubcommand(CommandParsing.Segment segment) {
        List<String> operands = segment.operands();
        for (int i = 0; i < operands.size(); i++) {
            String operand = operands.get(i);
            if (operand.startsWith("-")) {
                if (GIT_VALUE_OPTIONS.contains(operand)) {
                    i++;   // 跳过它带的那个值
                }
                continue;
            }
            return operand;
        }
        return "";
    }

    /** `curl ... | sh` 之外的另一条路：`sh -c "$(curl -s ...)"`。 */
    private static Verdict checkRemoteWipe(CommandParsing.Segment segment, CommandParsing.Segment ignored) {
        if (!SHELLS.contains(segment.program())) {
            return Verdict.allow();
        }
        for (String operand : segment.operands()) {
            if (operand.contains("curl") || operand.contains("wget")) {
                return Verdict.ask("把下载到的内容直接交给 shell 执行");
            }
        }
        return Verdict.allow();
    }

    /**
     * 凭据路径：不按"读者白名单"判定 —— `grep -r SECRET ~/.aws/credentials` 就是靠这条逃过去的。
     * 任何命令的操作数里出现凭据路径都停下来问人（绊线是绊线，问一句的代价远低于外泄）。
     */
    private static Verdict checkSecrets(CommandParsing.Segment segment, CommandParsing.Segment ignored) {
        for (String operand : segment.operands()) {
            if (isCredentialPath(operand)) {
                return Verdict.ask("读写凭据文件：" + operand);
            }
        }
        return Verdict.allow();
    }

    private static boolean isCredentialPath(String operand) {
        return operand.contains(".ssh/id_") || operand.contains(".ssh/id_rsa")
                || operand.contains(".aws/credentials") || operand.endsWith(".env");
    }

    /** `sh -c '...'` 与 `eval ...` 的实参是真命令，必须再解析一遍。 */
    private static Verdict checkNested(List<CommandParsing.Segment> segments, int depth) {
        for (CommandParsing.Segment segment : segments) {
            String inner = innerCommand(segment);
            if (inner == null) {
                continue;
            }
            if (inner.indexOf('$') >= 0 || inner.indexOf('`') >= 0) {
                return Verdict.ask("实参要到运行期才知道，无法安全解析");
            }
            if (depth >= MAX_DEPTH) {
                return Verdict.ask("嵌套过深的 shell 调用");
            }
            Verdict verdict = assess(inner, depth + 1);
            if (!verdict.allowed()) {
                return verdict;
            }
        }
        return Verdict.allow();
    }

    private static String innerCommand(CommandParsing.Segment segment) {
        boolean eval = segment.is("eval");
        if (!eval && !SHELLS.contains(segment.program())) {
            return null;
        }
        String argument = eval ? String.join(" ", segment.operands()) : afterShellDashC(segment);
        return argument == null || argument.trim().isEmpty() ? null : argument;
    }

    private static String afterShellDashC(CommandParsing.Segment segment) {
        List<String> operands = segment.operands();
        for (int i = 0; i < operands.size() - 1; i++) {
            if (operands.get(i).matches("-[a-z]*c[a-z]*")) {
                return operands.get(i + 1);
            }
        }
        return null;
    }

    /** `-r` 与 `-R` 都是递归（05-action §5.8.1 规则 10）：只认小写会漏掉 `rm -R /`。 */
    private static boolean recursive(CommandParsing.Segment segment) {
        return segment.hasFlag("r") || segment.hasFlag("R") || segment.hasFlag("recursive");
    }

    private static boolean hasCriticalTarget(CommandParsing.Segment segment) {
        for (String operand : segment.operands()) {
            if (operand.startsWith("-")) {
                continue;
            }
            String target = operand.endsWith("/") && operand.length() > 1
                    ? operand.substring(0, operand.length() - 1) : operand;
            if (CRITICAL_DIRS.contains(operand) || CRITICAL_DIRS.contains(target) || target.matches("/\\*.*")
                    || target.matches("~/\\*.*")) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyContains(List<String> values, String needle) {
        for (String value : values) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** 供审批文案使用：把原始命令按行截短，避免 TUI 被超长命令撑爆。 */
    public static String summarize(String commandLine) {
        String single = commandLine.replace('\n', ' ');
        return single.length() <= 500 ? single : single.substring(0, 500) + "…";
    }
}
