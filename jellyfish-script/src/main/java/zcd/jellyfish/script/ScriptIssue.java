package zcd.jellyfish.script;

/**
 * 一条脚本问题：出在哪个脚本、具体是什么。
 * <p>
 * <b>为什么必须带上来源</b>：这些问题会在桥接插件的启动汇总里成批出现，作者需要一眼看出该去改哪个脚本。
 * 只给一句「缺少 manifest.json」而不说是哪个目录，等于把定位工作推回给用户。
 * <p>
 * <b>为什么它既服务扫描也服务注册</b>：扫描期的问题是「清单读了但不可用」，注册期的问题是
 * 「清单可用但注册不上」（例如工具名与别的插件撞了）。两者的呈现方式完全一样——
 * 都是「某个脚本没能提供某项能力」——因此共用一种载体，桥接插件不必维护两张清单。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
public final class ScriptIssue {

    /** 来源（脚本目录名或路径）。 */
    private final String source;

    /** 问题描述。 */
    private final String message;

    /**
     * 构造问题。
     *
     * @param source  来源，不可为 {@code null}
     * @param message 问题描述，不可为 {@code null}
     */
    public ScriptIssue(String source, String message) {
        this.source = source;
        this.message = message;
    }

    /**
     * 获取来源。
     *
     * @return 来源
     */
    public String source() {
        return source;
    }

    /**
     * 获取问题描述。
     *
     * @return 问题描述
     */
    public String message() {
        return message;
    }

    @Override
    public String toString() {
        return source + ": " + message;
    }
}
