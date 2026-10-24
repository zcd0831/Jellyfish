package io.github.zcd0831.jellyfish.infrastructure.file;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人写资产的头部：`---` 包裹的 key: value。只支持本项目用到的极简子集，
 * 不引 YAML 库（规则出处：模块-基础设施层 §4 文件形态三分）。
 */
final class FrontMatter {

    private final Map<String, String> values;
    private final String body;

    private FrontMatter(Map<String, String> values, String body) {
        this.values = values;
        this.body = body;
    }

    static FrontMatter parse(String text) {
        String[] lines = text.split("\n", -1);
        if (lines.length == 0 || !"---".equals(lines[0].trim())) {
            return new FrontMatter(new LinkedHashMap<String, String>(), text);
        }
        Map<String, String> values = new LinkedHashMap<String, String>();
        int index = 1;
        for (; index < lines.length; index++) {
            String line = lines[index];
            if ("---".equals(line.trim())) {
                index++;
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                values.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
        StringBuilder body = new StringBuilder();
        for (int i = index; i < lines.length; i++) {
            body.append(lines[i]);
            if (i < lines.length - 1) {
                body.append('\n');
            }
        }
        return new FrontMatter(values, body.toString());
    }

    String get(String key) {
        return values.get(key);
    }

    List<String> list(String key) {
        String raw = values.get(key);
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<String>();
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        if (trimmed.isEmpty()) {
            return new ArrayList<String>();
        }
        List<String> out = new ArrayList<String>();
        for (String item : Arrays.asList(trimmed.split(","))) {
            String value = item.trim();
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }

    String body() {
        return body;
    }
}
