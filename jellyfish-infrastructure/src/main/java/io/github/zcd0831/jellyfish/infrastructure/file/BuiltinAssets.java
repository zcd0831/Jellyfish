package io.github.zcd0831.jellyfish.infrastructure.file;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * jar 内置资产：用 index.txt 列出相对路径，避免依赖"枚举 jar 目录"（不可移植）。
 * 规则出处：模块-基础设施层 §4（内置代理不落盘）、M04。
 */
final class BuiltinAssets {

    private static final String INDEX = "/builtin/index.txt";

    private BuiltinAssets() {
    }

    static List<String> list(String prefix) {
        List<String> out = new ArrayList<String>();
        String index = readText(INDEX);
        if (index == null) {
            return out;
        }
        for (String line : index.split("\n")) {
            String path = line.trim();
            if (!path.isEmpty() && path.startsWith(prefix)) {
                out.add(path);
            }
        }
        return out;
    }

    static String readText(String path) {
        InputStream stream = BuiltinAssets.class.getResourceAsStream(path);
        if (stream == null) {
            return null;
        }
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取内置资产失败：" + path, e);
        }
    }
}
