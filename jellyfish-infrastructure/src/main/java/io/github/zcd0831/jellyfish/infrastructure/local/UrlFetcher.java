package io.github.zcd0831.jellyfish.infrastructure.local;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * `fetch_url` 的出站 GET：取回纯文本即可，不做浏览器那一套。
 * 规则出处：05-action §5.8.1 规则 9（只 http/https、默认 8000 字符、失败当普通结果）。
 */
public final class UrlFetcher {

    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_SECONDS = 30;
    private static final Pattern SCRIPT = Pattern.compile("(?is)<script.*?</script>|<style.*?</style>");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build();

    public String fetch(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("只支持 http/https：" + url);
        }
        Request request = new Request.Builder().url(url).header("User-Agent", "jellyfish/1.0").get().build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                return "HTTP " + response.code() + "：" + truncate(text);
            }
            return toPlainText(text);
        } catch (IOException e) {
            // 网络失败是"一种工具结果"，不是领域状态（05-action §5.8.1 规则 3）
            return "抓取失败：" + e.getMessage();
        }
    }

    /** 模型读的是内容，不是标签；脚本与样式是噪音，直接丢掉。 */
    private static String toPlainText(String html) {
        String text = SCRIPT.matcher(html).replaceAll(" ");
        text = COMMENT.matcher(text).replaceAll(" ");
        text = TAG.matcher(text).replaceAll(" ");
        text = text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        text = BLANK_LINES.matcher(text).replaceAll("\n\n");
        return text.trim();
    }

    private static String truncate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}
