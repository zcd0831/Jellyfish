package zcd.jellyfish.infra.support;

import zcd.jellyfish.infra.llm.LlmClientFactory;

/**
 * 配置文件 provider.type 中使用的类型常量。
 * <p>
 * 这些常量同时作为 {@link LlmClientFactory} 注册表的 key，必须保持一致。
 *
 * @author zcd
 */
public final class ProviderTypes {

    public static final String OPENAI = "openai";
    public static final String DEEPSEEK = "deepseek";
    public static final String MINIMAX = "minimax";
    public static final String GEMINI = "gemini";
    /** gemini 的别名 */
    public static final String GOOGLE = "google";
    public static final String CLAUDE = "claude";
    /** claude 的别名 */
    public static final String ANTHROPIC = "anthropic";

    private ProviderTypes() {
    }
}
