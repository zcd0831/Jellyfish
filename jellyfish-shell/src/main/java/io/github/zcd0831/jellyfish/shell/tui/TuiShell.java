package io.github.zcd0831.jellyfish.shell.tui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * TUI 外壳的最小形态：S0 只验证"能跑起来、能收发一行文本"，用例在 S1 接入。
 * 规则出处：技术决策 D12.1（本期只做 TUI）。
 */
public final class TuiShell {

    private final PrintStream out;

    public TuiShell(PrintStream out) {
        this.out = out;
    }

    public void run() {
        out.println("Jellyfish 1.0.0-alpha（S0 骨架：用例尚未接入）");
        out.println("输入 exit 退出");
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if ("exit".equals(line.trim())) {
                    return;
                }
                out.println("收到：" + line);
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取终端输入失败", e);
        }
    }
}
