package io.github.zcd0831.jellyfish.application.action;

/**
 * 工具执行结果：**没有 isSuccess**——失败也是一种结果，回灌模型继续。
 * 规则出处：05-action §5.6（ToolExecOutcome）、§5.8.1 规则 3。
 */
public final class ToolExecOutcome {

    private final String content;
    private final boolean truncated;
    private final String resourceUri;

    public ToolExecOutcome(String content, boolean truncated, String resourceUri) {
        this.content = content;
        this.truncated = truncated;
        this.resourceUri = resourceUri;
    }

    public static ToolExecOutcome plain(String content) {
        return new ToolExecOutcome(content, false, null);
    }

    public String content() {
        return content;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public String resourceUri() {
        return resourceUri;
    }
}
