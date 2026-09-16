package zcd.jellyfish.plugin.project;

import java.util.Objects;

/**
 * 探测到的项目约定文件：名称与大小。
 * <p>
 * <b>为什么要带大小</b>：它是「内联原文还是只给路径」这条分流的唯一判据，也是路径指引里
 * 「这个文件有多大，该分段读」这句话的数据来源。先 {@code stat} 拿到大小、再决定要不要读正文，
 * 顺带避免了为一个大文件白读一遍磁盘。
 * <p>
 * 不可变，可安全跨线程传递。
 *
 * @author zcd
 */
final class ConventionFile {

    /** 文件名（也是给模型的相对路径）。 */
    private final String name;

    /**
     * 文件大小，单位字节；探测时保证大于 {@code 0}，取不到大小时为 {@code -1}。
     * <p>
     * 用负数而不是 {@code 0} 表示「大小未知」：内联的前提是「确定装得下」，
     * 而未知大小恰恰是装不下这件事不可判定，两者必须能分辨。
     */
    private final long sizeInBytes;

    /**
     * 构造约定文件。
     *
     * @param name        文件名，不可为 {@code null}
     * @param sizeInBytes 文件大小（字节）
     */
    ConventionFile(String name, long sizeInBytes) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.sizeInBytes = sizeInBytes;
    }

    /**
     * 获取文件名。
     *
     * @return 文件名
     */
    String name() {
        return name;
    }

    /**
     * 获取文件大小。
     *
     * @return 字节数；大小未知时为负数
     */
    long sizeInBytes() {
        return sizeInBytes;
    }

    /**
     * 判断这份文件是否装得进给定上限。
     * <p>
     * 判定放在这里而不是调用点：它同时管住「大于上限」与「大小未知」两种不可内联的情形，
     * 散到调用点就会漏掉后一种。
     *
     * @param limit 上限（字节）
     * @return 大小已知且不超过上限时返回 {@code true}
     */
    boolean fitsWithin(int limit) {
        return sizeInBytes >= 0 && sizeInBytes <= limit;
    }
}
