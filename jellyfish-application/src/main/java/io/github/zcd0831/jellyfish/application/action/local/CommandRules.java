package io.github.zcd0831.jellyfish.application.action.local;

import java.util.Arrays;
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

    private static Verdict checkSegment(CommandParsing.Segment segment, CommandParsing.Segment upstream) {
        String program = segment.program();
        if (program.isEmpty()) {
            return Verdict.allow();
        }
        if (program.startsWith("mkfs") || program.equals("shred") || program.equals("fdisk")
                || program.equals("shutdown") || program.equals("reboot") || program.equals("halt")) {
            return Verdict.ask("危险命令：" + program);
        }
        if (program.equals("sudo") || program.equals("su") || program.equals("doas")) {
            return Verdict.ask("提权执行：" + program);
        }
        if (program.equals("dd") && anyContains(segment.operands(), "of=/dev/")) {
            return Verdict.ask("原地覆写设备");
        }
        if (anyContains(segment.redirects(), "/dev/sd") || anyContains(segment.redirects(), "/dev/disk")) {
            return Verdict.ask("写入块设备");
        }
        if (program.equals("chmod") && segment.hasFlag("R") && hasCriticalTarget(segment)) {
            return Verdict.ask("递归改动系统目录权限");
        }
        if (program.equals("rm") && recursive(segment) && hasCriticalTarget(segment)) {
            return Verdict.ask("递归删除系统目录或家目录");
        }
        if (program.equals("kill") && anyContains(segment.operands(), "-1")) {
            return Verdict.ask("杀全部进程");
        }
        if (program.equals("git")) {
            Verdict git = checkGit(segment);
            if (!git.allowed()) {
                return git;
            }
        }
        if (program.equals("find") && (segment.hasFlag("delete") || segment.hasFlag("exec"))) {
            return Verdict.ask("find 直接删除或执行命令");
        }
        if (program.equals("xargs") && DESTRUCTIVE.contains(segment.firstArgument())) {
            return Verdict.ask("xargs 派生删除类命令");
        }
        if (SHELLS.contains(program) && upstream != null && "|".equals(segment.separator())
                && DOWNLOADERS.contains(upstream.program())) {
            return Verdict.ask("把下载到的内容直接交给 shell 执行");
        }
        Verdict sql = checkSql(segment);
        if (!sql.allowed()) {
            return sql;
        }
        Verdict remote = checkRemoteWipe(segment);
        if (!remote.allowed()) {
            return remote;
        }
        return checkSecrets(segment);
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
    private static Verdict checkSql(CommandParsing.Segment segment) {
        for (String operand : segment.operands()) {
            if (DESTRUCTIVE_SQL.matcher(operand).find()) {
                return Verdict.ask("破坏性 SQL：" + operand);
            }
        }
        return Verdict.allow();
    }

    private static Verdict checkGit(CommandParsing.Segment segment) {
        String sub = segment.firstArgument();
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

    /** `curl ... | sh` 之外的另一条路：`sh -c "$(curl -s ...)"`。 */
    private static Verdict checkRemoteWipe(CommandParsing.Segment segment) {
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

    private static Verdict checkSecrets(CommandParsing.Segment segment) {
        boolean reads = segment.is("cat") || segment.is("less") || segment.is("head") || segment.is("tail")
                || segment.is("cp") || segment.is("scp") || segment.is("base64") || segment.is("tar");
        boolean sends = segment.is("curl") || segment.is("wget") || segment.is("nc") || segment.is("mail");
        if (!reads && !sends) {
            return Verdict.allow();
        }
        for (String operand : segment.operands()) {
            if (operand.contains(".ssh/id_") || operand.contains(".aws/credentials") || operand.endsWith(".env")
                    || operand.contains(".ssh/id_rsa")) {
                return Verdict.ask("读写凭据文件：" + operand);
            }
        }
        return Verdict.allow();
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

    private static boolean recursive(CommandParsing.Segment segment) {
        return segment.hasFlag("r") || segment.hasFlag("recursive");
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
