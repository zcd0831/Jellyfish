package io.github.zcd0831.jellyfish.application.shared;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用层要读模型给的 `arguments`（一段 JSON 字符串），但不能引 JSON 库：
 * 只解析"对象/数组/标量"这四种结构，足够工具入参使用。
 */
public final class Json {

    private final String text;
    private int index;

    private Json(String text) {
        this.text = text;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        Json parser = new Json(json);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.index < parser.text.length()) {
            throw new IllegalArgumentException("JSON 末尾有多余内容，位置 " + parser.index);
        }
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<String, Object>();
    }

    private Object readValue() {
        skipWhitespace();
        char c = peek();
        if (c == '{') {
            return readObject();
        }
        if (c == '[') {
            return readArray();
        }
        if (c == '"') {
            return readString();
        }
        if (text.startsWith("true", index)) {
            index += 4;
            return Boolean.TRUE;
        }
        if (text.startsWith("false", index)) {
            index += 5;
            return Boolean.FALSE;
        }
        if (text.startsWith("null", index)) {
            index += 4;
            return null;
        }
        return readNumber();
    }

    private Map<String, Object> readObject() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            index++;
            return out;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            out.put(key, readValue());
            skipWhitespace();
            char c = text.charAt(index++);
            if (c == '}') {
                return out;
            }
            if (c != ',') {
                throw new IllegalArgumentException("JSON 对象格式错误，位置 " + index);
            }
        }
    }

    private List<Object> readArray() {
        List<Object> out = new ArrayList<Object>();
        expect('[');
        skipWhitespace();
        if (peek() == ']') {
            index++;
            return out;
        }
        while (true) {
            out.add(readValue());
            skipWhitespace();
            char c = text.charAt(index++);
            if (c == ']') {
                return out;
            }
            if (c != ',') {
                throw new IllegalArgumentException("JSON 数组格式错误，位置 " + index);
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (index < text.length()) {
            char c = text.charAt(index++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escaped = text.charAt(index++);
            switch (escaped) {
                case 'n':
                    out.append('\n');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case 'b':
                    out.append('\b');
                    break;
                case 'f':
                    out.append('\f');
                    break;
                case 'u':
                    out.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                    index += 4;
                    break;
                default:
                    out.append(escaped);
            }
        }
        throw new IllegalArgumentException("JSON 字符串未闭合");
    }

    private Object readNumber() {
        int start = index;
        while (index < text.length() && "-+.eE0123456789".indexOf(text.charAt(index)) >= 0) {
            index++;
        }
        String raw = text.substring(start, index);
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("JSON 数值格式错误，位置 " + start);
        }
        if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
            try {
                return Long.valueOf(raw);
            } catch (NumberFormatException ignored) {
                return Double.valueOf(raw);
            }
        }
        return Double.valueOf(raw);
    }

    private void skipWhitespace() {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
    }

    private char peek() {
        if (index >= text.length()) {
            throw new IllegalArgumentException("JSON 意外结束");
        }
        return text.charAt(index);
    }

    private void expect(char expected) {
        if (index >= text.length() || text.charAt(index) != expected) {
            throw new IllegalArgumentException("JSON 需要 '" + expected + "'，位置 " + index);
        }
        index++;
    }
}
