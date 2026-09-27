package zcd.jellyfish.server.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条请求上被路径模板捕获到的变量。
 * <p>
 * 由 {@link Router} 在匹配成功时构造，只含模板里声明过的那些名字；因此处理器拿到的键集合是
 * 「模板说了什么」而不是「路径碰巧长什么样」，拼错参数名会得到 {@code null} 而不是拿到别的段。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class PathParams {

    /** 空路径参数。 */
    private static final PathParams EMPTY = new PathParams(Collections.<String, String>emptyMap());

    /** 变量表。 */
    private final Map<String, String> values;

    /**
     * 构造路径参数。
     *
     * @param values 变量表，可为 {@code null}（等价空集）
     */
    public PathParams(Map<String, String> values) {
        this.values = values == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(values));
    }

    /**
     * 取空路径参数。
     *
     * @return 不含任何变量的实例
     */
    public static PathParams empty() {
        return EMPTY;
    }

    /**
     * 取一个变量。
     *
     * @param name 变量名（模板里 {@code {name}} 的 {@code name}）
     * @return 变量值；模板里没有这个名字时为 {@code null}
     */
    public String get(String name) {
        return values.get(name);
    }

    /**
     * 取全部变量。
     *
     * @return 不可修改的变量表，保证非 {@code null}
     */
    public Map<String, String> asMap() {
        return values;
    }
}
