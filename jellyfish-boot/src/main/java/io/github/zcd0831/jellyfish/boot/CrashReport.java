package io.github.zcd0831.jellyfish.boot;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 崩溃现场快照：把最外层没被接住的异常连同运行环境落成一个文件，供事后排查。
 * 只写文件、不抛异常——记录手段本身失败不能掩盖原始故障。
 * 约定出处：Jellyfish/AGENTS.md「错误表达：…冒到最外层写 error 日志 + 现场快照」。
 */
public final class CrashReport {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private CrashReport() {
    }

    /** 写入 {@code logs/crash-<时间戳>.txt}；返回落盘路径，失败时返回 null。 */
    public static Path write(Path logsDir, String kind, Throwable error, String dataDir) {
        String stamp = STAMP.format(LocalDateTime.now());
        Path file = logsDir.resolve("crash-" + stamp + ".txt");
        try {
            Files.createDirectories(logsDir);
            Files.write(file, lines(kind, error, dataDir, stamp), StandardCharsets.UTF_8);
            return file;
        } catch (IOException | RuntimeException cannotWrite) {
            return null;
        }
    }

    static List<String> lines(String kind, Throwable error, String dataDir, String stamp) {
        List<String> out = new ArrayList<String>();
        out.add("time=" + stamp);
        out.add("kind=" + kind);
        out.add("data-dir=" + dataDir);
        out.add("java=" + System.getProperty("java.version"));
        out.add("os=" + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        out.add("");
        out.add("---- 现场快照 · 异常链 ----");
        Throwable cursor = error;
        while (cursor != null) {
            out.add(cursor.getClass().getName() + ": " + cursor.getMessage());
            cursor = cursor.getCause();
        }
        out.add("");
        out.add("---- 栈 ----");
        StringWriter buffer = new StringWriter();
        PrintWriter writer = new PrintWriter(buffer);
        error.printStackTrace(writer);
        writer.flush();
        for (String line : buffer.toString().split("\\r?\\n")) {
            out.add(line);
        }
        return out;
    }
}
