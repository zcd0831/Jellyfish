package io.github.zcd0831.jellyfish.application.shared;

/**
 * 应用层需要把工具调用落成一行文本（Record 只存字符串），但不能引 Jackson（D02）。
 * 只做 JSON 字符串转义这一件事。
 */
public final class JsonText {

    private JsonText() {
    }

    public static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    out.append(c);
            }
        }
        return out.toString();
    }
}
