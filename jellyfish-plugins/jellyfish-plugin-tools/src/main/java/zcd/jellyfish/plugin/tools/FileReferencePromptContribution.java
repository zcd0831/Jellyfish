package zcd.jellyfish.plugin.tools;

import zcd.jellyfish.api.extension.ExtensionHandler;
import zcd.jellyfish.api.extension.PromptContribution;
import zcd.jellyfish.api.extension.PromptContributionRequest;

/**
 * 提示词贡献：把「{@code @路径} 是文件引用」这条约定告诉模型。
 * <p>
 * <b>为什么需要它</b>：{@code @} 只做补全，不内联内容（不展开是刻意的：文件内容一律经
 * {@code read_file} 读取，从而复用权限判定、单行超限报错、大小上限与输出落盘）。
 * 于是模型必须知道「用户这样写 = 去读这个文件」，否则它只会把 {@code @a.txt} 当成一段普通文字。
 * <p>
 * <b>常驻而非按需</b>：只有几十个 token，而按需注入需要内核在组装期扫描消息、判断「本轮用没用 {@code @}」，
 * 成本与复杂度都更高，还会让 system prompt 在不同轮次间跳变（缓存的 prefix 失效）。
 * <p>
 * <b>语气上强调「不要猜」</b>：模型面对一个路径时最常见的失败模式是照着文件名编内容，
 * 因此这里把「读不到就说读不到」直接写进约定。
 * <p>
 * 无状态，可安全复用。
 *
 * @author zcd
 */
final class FileReferencePromptContribution
        implements ExtensionHandler<PromptContributionRequest, PromptContribution> {

    /** 贡献文本。 */
    private static final String TEXT =
            "用户消息里的 `@路径` 表示引用了工作目录下的文件。看到这种写法时，请先用 read_file 读取该文件，"
                    + "再基于真实内容回答；不要凭文件名猜测内容，读不到就如实说明。";

    @Override
    public PromptContribution handle(PromptContributionRequest request) {
        return PromptContribution.of(TEXT);
    }
}
