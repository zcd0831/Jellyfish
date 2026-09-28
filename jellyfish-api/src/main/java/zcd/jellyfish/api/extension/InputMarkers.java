package zcd.jellyfish.api.extension;

import zcd.jellyfish.api.JellyfishException;

/**
 * 输入标记字符的共享校验：指令标记（行首）与引用标记（行内）用的是同一套规则。
 * <p>
 * <b>为什么必须是单个非空白字符</b>：标记是注册时的路由键，也是外壳扫描输入时的判据。
 * 允许多字符会把「路由键」变成「前缀匹配」，注册表的同键唯一语义就再也保证不了独占性；
 * 允许空白则会让标记在输入里不可见。两者都是当场就该报错的编程错误。
 * <p>
 * 放在本包而不是 {@code infra}：请求对象在构造期就要校验，而 {@code api} 不依赖任何内部模块，
 * 校验规则因此只能与请求类型待在一起。
 *
 * @author zcd
 */
final class InputMarkers {

    /**
     * 工具类，禁止实例化。
     */
    private InputMarkers() {
    }

    /**
     * 校验并返回输入标记。
     *
     * @param marker 标记字符，可为 {@code null}
     * @return 校验通过的标记
     * @throws JellyfishException 为空、长度不为 1，或该字符是空白时抛出
     */
    static String requireMarker(String marker) {
        if (marker == null || marker.isEmpty()) {
            throw new JellyfishException("input marker must not be blank");
        }
        if (marker.length() != 1) {
            throw new JellyfishException("input marker must be exactly one character: " + marker);
        }
        if (Character.isWhitespace(marker.charAt(0))) {
            throw new JellyfishException("input marker must not be whitespace: " + marker);
        }
        return marker;
    }
}
