package io.github.zcd0831.jellyfish.shell.tui;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * code → 人话。缺 key 回退显示 code 本身，保证不崩。
 * 规则出处：技术决策 D11（文案放 shell）。
 */
final class Messages {

    private static final Properties TEXT = load();

    private Messages() {
    }

    static String text(String code) {
        String value = TEXT.getProperty(code);
        return value == null ? code : value;
    }

    private static Properties load() {
        Properties properties = new Properties();
        InputStream stream = Messages.class.getResourceAsStream("/messages.properties");
        if (stream == null) {
            return properties;
        }
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException ignored) {
            // 文案加载失败不影响运行：一律回退到 code
        }
        return properties;
    }
}
