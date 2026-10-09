package zcd.jellyfish.server.http;

/**
 * 请求路径的归一化：把「同一个接口的不同写法」收成同一种写法。
 * <p>
 * <b>为什么单独抽出来</b>：路由（{@link Router}）与守门人（{@link ApiKeyGuard}）都要回答
 * 「这个请求打的是哪个接口」，各写一份就会出现「路由认得、守门人不认得」的分裂。实际发生过的形态是：
 * {@code GET /health/} 在路由眼里就是 {@code /health}（它按段匹配、忽略尾斜杠），而守门人按原文
 * 精确比对，于是启用密钥时探活请求先拿到 401——路由明明认得那个地址。
 * <p>
 * <b>只做一件事</b>：去掉末尾多余的 {@code /}（根路径保持原样）。不做大小写折叠、不解码百分号：
 * 那些属于另一层，混进来会让「什么算同一条路径」变得难以解释。
 *
 * @author zcd
 */
final class RequestPath {

    /**
     * 常量类，禁止实例化。
     */
    private RequestPath() {
    }

    /**
     * 归一化请求路径。
     *
     * @param path 原始路径，可为 {@code null}
     * @return 去掉末尾斜杠后的路径；入参为 {@code null} 时返回 {@code null}
     */
    static String normalize(String path) {
        if (path == null) {
            return null;
        }
        String normalized = path;
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
