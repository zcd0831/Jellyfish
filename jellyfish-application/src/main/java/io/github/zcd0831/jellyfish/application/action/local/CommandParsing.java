package io.github.zcd0831.jellyfish.application.action.local;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 命令行的浅结构解析：不做完整语法树，只把"一条命令"拆成命令段。
 * 判定危险只需要 程序 / 参数 / 操作数 / 重定向 / 段间拓扑，不需要 AST 的其余部分。
 * 规则出处：05-action §5.8.1 规则 10。
 */
public final class CommandParsing {

    /** 只作为前缀出现的包装命令：真正的程序是它后面那个词。 */
    private static final List<String> WRAPPERS = Arrays.asList("env", "command", "nohup", "time", "exec");
    private static final String[] OPERATORS = {"&&", "||", ">>", "2>", "&>", "<<", "|", ";", "&", ">", "<", "(", ")"};

    private CommandParsing() {
    }

    /** 一段命令：前导分隔符决定它与上一段的关系（管道、条件、顺序）。 */
    public static final class Segment {

        private final String separator;
        private final List<String> words;
        private final List<String> redirects;
        private final boolean dynamic;

        Segment(String separator, List<String> words, List<String> redirects, boolean dynamic) {
            this.separator = separator;
            this.words = Collections.unmodifiableList(words);
            this.redirects = Collections.unmodifiableList(redirects);
            this.dynamic = dynamic;
        }

        public String separator() {
            return separator;
        }

        /** 程序名（已去掉目录前缀与 env/command 之类的包装）。 */
        public String program() {
            return words.isEmpty() ? "" : baseName(words.get(0));
        }

        /** 操作数：去掉程序名后的词，重定向目标不在其中。 */
        public List<String> operands() {
            return words.size() <= 1 ? Collections.<String>emptyList() : words.subList(1, words.size());
        }

        public List<String> redirects() {
            return redirects;
        }

        /** 含 $( ) / 反引号 / 变量展开：内容要到运行期才知道。 */
        public boolean dynamic() {
            return dynamic;
        }

        public boolean is(String program) {
            return program().equals(program);
        }

        public boolean hasFlag(String... flags) {
            for (String word : operands()) {
                if (word.startsWith("-") && !word.startsWith("--")) {
                    for (String flag : flags) {
                        if (word.substring(1).contains(flag)) {
                            return true;
                        }
                    }
                }
                for (String flag : flags) {
                    if (("--" + flag).equals(word) || word.contains("--" + flag + "=")) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** 第一个不是选项的词（`git push --force origin main` 里的 `push`）。 */
        public String firstArgument() {
            for (String word : operands()) {
                if (!word.startsWith("-")) {
                    return word;
                }
            }
            return "";
        }
    }

    public static final class Result {

        private final boolean parsed;
        private final List<Segment> segments;
        private final boolean functionDefinition;
        private final String reason;

        private Result(boolean parsed, List<Segment> segments, boolean functionDefinition, String reason) {
            this.parsed = parsed;
            this.segments = Collections.unmodifiableList(segments);
            this.functionDefinition = functionDefinition;
            this.reason = reason;
        }

        public boolean parsed() {
            return parsed;
        }

        /** `name() { ... }`：只服务于"函数定义 + 后台执行 = 疑似 fork 炸弹"这一条。 */
        public boolean hasFunctionDefinition() {
            return functionDefinition;
        }

        public List<Segment> segments() {
            return segments;
        }

        public String reason() {
            return reason;
        }
    }

    public static Result parse(String line) {
        List<String> tokens = new ArrayList<String>();
        String failure = tokenize(line == null ? "" : line, tokens);
        if (failure != null) {
            return new Result(false, Collections.<Segment>emptyList(), false, failure);
        }
        return new Result(true, split(tokens), hasFunctionDefinition(tokens), null);
    }

    /** 重定向属于**写它的那段命令**，所以要紧挨着当前段累积，而不是挂到下一段上。 */
    private static List<Segment> split(List<String> tokens) {
        List<Segment> segments = new ArrayList<Segment>();
        List<String> words = new ArrayList<String>();
        List<String> redirects = new ArrayList<String>();
        String separator = "";
        String pending = null;
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (isRedirect(token)) {
                if (i + 1 < tokens.size()) {
                    redirects.add(tokens.get(++i));
                }
                continue;
            }
            if (isOperator(token)) {
                if (!words.isEmpty()) {
                    pending = token;
                }
                continue;
            }
            if (pending != null) {
                segments.add(new Segment(separator, unwrap(words), redirects, isDynamic(words)));
                words = new ArrayList<String>();
                redirects = new ArrayList<String>();
                separator = pending;
                pending = null;
            }
            words.add(token);
        }
        if (!words.isEmpty()) {
            segments.add(new Segment(separator, unwrap(words), redirects, isDynamic(words)));
        }
        return segments;
    }

    /** `f ( )` 形式的定义：`(` `)` 是独立 token，函数名就在它们前面。 */
    private static boolean hasFunctionDefinition(List<String> tokens) {
        for (int i = 1; i + 1 < tokens.size(); i++) {
            if ("(".equals(tokens.get(i)) && ")".equals(tokens.get(i + 1))
                    && tokens.get(i - 1).matches("[A-Za-z0-9_:.\\-]+")) {
                return true;
            }
        }
        return false;
    }

    /** 展开在运行期才求值：`$(...)`、反引号、`$VAR` 都会留下这些字符。 */
    private static boolean isDynamic(List<String> words) {
        for (String word : words) {
            if (word.indexOf('$') >= 0 || word.indexOf('`') >= 0) {
                return true;
            }
        }
        return false;
    }

    /** `FOO=1 env rm -rf /` 与 `command rm` 的真正程序都在后面：判定必须看得见它。 */
    private static List<String> unwrap(List<String> words) {
        int start = 0;
        while (start < words.size() - 1) {
            String word = words.get(start);
            if (word.matches("[A-Za-z_][A-Za-z0-9_]*=.*") || WRAPPERS.contains(word)) {
                start++;
                continue;
            }
            break;
        }
        return new ArrayList<String>(words.subList(start, words.size()));
    }

    private static boolean isOperator(String token) {
        for (String operator : OPERATORS) {
            if (operator.equals(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRedirect(String token) {
        return token.contains(">") || token.contains("<");
    }

    private static String baseName(String word) {
        int slash = word.lastIndexOf('/');
        return slash < 0 ? word : word.substring(slash + 1);
    }

    /** 引号与转义在这里消解；看不懂就明确失败（fail-closed 的前提）。 */
    private static String tokenize(String line, List<String> tokens) {
        StringBuilder word = new StringBuilder();
        boolean inWord = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote == '\'') {
                if (c == '\'') {
                    quote = 0;
                } else {
                    word.append(c);
                }
                continue;
            }
            if (quote == '"') {
                if (c == '"') {
                    quote = 0;
                } else if (c == '\\' && i + 1 < line.length()) {
                    word.append(line.charAt(++i));
                } else {
                    word.append(c);
                }
                continue;
            }
            if (c == '\\' && i + 1 < line.length()) {
                word.append(line.charAt(++i));
                inWord = true;
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                inWord = true;
                continue;
            }
            if (c == '#' && !inWord) {
                while (i < line.length() && line.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '\n') {
                c = ';';
            }
            if (isOperatorStart(c)) {
                if (inWord) {
                    tokens.add(word.toString());
                    word.setLength(0);
                    inWord = false;
                }
                String operator = operatorAt(line, i);
                if ("<<".equals(operator)) {
                    // heredoc 的正文无法与命令区分开：看不懂就问人
                    return "不支持 heredoc 的命令";
                }
                tokens.add(operator);
                i += operator.length() - 1;
                continue;
            }
            if (c == ' ' || c == '\t') {
                if (inWord) {
                    tokens.add(word.toString());
                    word.setLength(0);
                    inWord = false;
                }
                continue;
            }
            word.append(c);
            inWord = true;
        }
        if (quote != 0) {
            return "引号未闭合";
        }
        if (inWord) {
            tokens.add(word.toString());
        }
        return null;
    }

    private static boolean isOperatorStart(char c) {
        return c == '|' || c == '&' || c == ';' || c == '>' || c == '<' || c == '(' || c == ')';
    }

    private static String operatorAt(String line, int index) {
        for (String operator : OPERATORS) {
            if (line.startsWith(operator, index)) {
                return operator;
            }
        }
        return String.valueOf(line.charAt(index));
    }

}
